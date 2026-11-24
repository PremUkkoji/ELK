# Docker Compose to Kubernetes (Minikube)

This guide documents the local Kubernetes version of the ELK demo currently
run with Docker Compose. The Compose setup remains available from the
repository root; these instructions deploy a separate stack to Minikube.

## Resulting topology

All namespaced resources are in `elk-k8s`.

| Component | Kubernetes resource | Replicas | Storage / access |
|---|---|---:|---|
| Elasticsearch 8.3.3 | Deployment and ClusterIP Service | 1 | `elasticsearch-data` PVC, 1 GiB |
| Logstash 8.3.3 | Deployment, ConfigMap, ClusterIP Service | 1 | Reads producer logs read-only; `logstash-data` PVC stores sincedb and persistent queue |
| Spring Boot log producer | Deployment | 2 | Writes one hostname-named JSON file per pod to the shared `producer-logs` PVC |
| Kibana 8.3.3 | Deployment and NodePort Service | 1 | Exposed on NodePort 30601; use the Minikube service tunnel on macOS with Docker driver |

The Minikube `standard` StorageClass provisions `ReadWriteOnce` claims. The
producer and Logstash pods can share the `producer-logs` claim here because
Minikube has one node and all pods run on it. If you move to a multi-node
cluster, use a storage provisioner that supports `ReadWriteMany`, or replace
file sharing with a log collector and JSON written to stdout.

The producer's container filesystem is ephemeral; the JSON files persist on
the mounted PVC. Elasticsearch data likewise persists on its PVC if the pod
is replaced. The Elasticsearch workload is a one-replica Deployment for this
single-node learning setup, not a multi-node Elasticsearch design.

## Requirements and images

The tested Minikube setup uses a Docker driver on macOS and a single node. Check
the cluster and StorageClass:

```sh
minikube status
kubectl config current-context
kubectl get nodes
kubectl get storageclass
```

Elastic images use version `8.3.3`. Pull the registry images one at a time into
Minikube's image store:

```sh
minikube image pull docker.elastic.co/elasticsearch/elasticsearch:8.3.3
minikube image pull docker.elastic.co/logstash/logstash:8.3.3
minikube image pull docker.elastic.co/kibana/kibana:8.3.3
```

Build the producer image from this repository directly into Minikube. Do not
rely on an identically named image in Docker Desktop; Minikube's container
runtime has a separate image store:

```sh
minikube image build -t elk-springboot-logger:local ./springboot-logger
minikube image ls
```

The producer Deployment uses `imagePullPolicy: Never`, so it must have this
exact image tag available in Minikube before applying the Deployment.

## Manifests

- [`namespace.yaml`](./namespace.yaml) creates the `elk-k8s` namespace. A
  Namespace is cluster-scoped, so the Namespace object itself does not have a
  `metadata.namespace`; all other namespaced resources explicitly set it.
- [`elasticsearch.yaml`](./elasticsearch.yaml) creates the Elasticsearch PVC,
  Deployment, and internal service. The pod runs single-node mode with
  Elasticsearch security disabled, matching the current Compose demo.
- [`storage.yaml`](./storage.yaml) creates the shared producer JSON log PVC and
  the Logstash state PVC.
- [`logstash.yaml`](./logstash.yaml) defines `logstash.yml` and the file-input
  pipeline in a ConfigMap, then mounts that configuration, the producer logs,
  and persistent Logstash state into one Logstash pod. Logstash reads
  `/usr/share/logstash/logs/*.json`, tracks offsets in
  `/usr/share/logstash/data/springboot-sincedb`, uses a persistent queue under
  `/usr/share/logstash/data/queue`, and writes to the `springboot-%{app}` index
  in Elasticsearch.
- [`springboot-logger.yaml`](./springboot-logger.yaml) creates two producer
  replicas using `elk-springboot-logger:local`. The pod name is injected as
  `HOSTNAME` so each pod writes a separate file on the shared volume.
- [`kibana.yaml`](./kibana.yaml) creates one Kibana pod and a NodePort service
  on port `30601`.

### Important workload settings

- Elasticsearch uses a 1 GiB RWO claim at
  `/usr/share/elasticsearch/data`, `discovery.type=single-node`, and
  `xpack.security.enabled=false`.
- Logstash uses a 1 GiB RWO claim at `/usr/share/logstash/data`. Its
  Deployment uses the `Recreate` strategy because a normal rolling update can
  briefly start two Logstash pods against the same `path.data` volume. Logstash
  refuses to start a second instance using that data directory.
- Producer log files use a separate 1 GiB RWO claim. Producers mount it
  read-write at `/var/log/springboot`; Logstash mounts the same claim
  read-only at `/usr/share/logstash/logs`.
- Elasticsearch and Logstash are internal ClusterIP Services. Only Kibana is
  given a NodePort.
- Resource requests/limits and readiness/startup probes are defined in the
  component manifests.

## Deploy step by step

Run commands from the repository root.

### 1. Create the namespace

```sh
kubectl apply -f k8s/namespace.yaml
kubectl get namespace elk-k8s
```

### 2. Deploy Elasticsearch

```sh
kubectl apply -f k8s/elasticsearch.yaml
kubectl -n elk-k8s rollout status deployment/elasticsearch-deployment
kubectl -n elk-k8s get deployment,service,pvc
```

The PVC should become `Bound` and the Elasticsearch Deployment should show
`1/1` available before continuing.

### 3. Create shared and Logstash storage, then deploy Logstash

```sh
kubectl apply -f k8s/storage.yaml
kubectl -n elk-k8s get pvc
kubectl apply -f k8s/logstash.yaml
kubectl -n elk-k8s rollout status deployment/logstash-deployment
kubectl -n elk-k8s get deployment,service,pvc
```

Both `producer-logs` and `logstash-data` should be `Bound`. Logstash should
become `1/1` available and connect to
`elasticsearch-service.elk-k8s.svc.cluster.local:9200`.

### 4. Build and deploy two producer pods

Build the image as described above, then apply the Deployment:

```sh
minikube image build -t elk-springboot-logger:local ./springboot-logger
kubectl apply -f k8s/springboot-logger.yaml
kubectl -n elk-k8s rollout status deployment/springboot-logger
kubectl -n elk-k8s get pods -l app=springboot-logger
```

There should be two ready producer pods. Each writes newline-delimited JSON
events to its own file on `producer-logs`.

### 5. Pull Kibana and deploy it

```sh
minikube image pull docker.elastic.co/kibana/kibana:8.3.3
kubectl apply -f k8s/kibana.yaml
kubectl -n elk-k8s rollout status deployment/kibana-deployment
kubectl -n elk-k8s get service kibana-service
```

### 6. Check all manifests and workloads

The API server can validate every manifest without changing resources:

```sh
kubectl apply --dry-run=server -f k8s/
```

Check final pod and storage status:

```sh
kubectl -n elk-k8s get pods,services,pvc -o wide
```

Expected replicas: Elasticsearch `1/1`, Logstash `1/1`, Kibana `1/1`, and
`springboot-logger` `2/2`. All three PVCs should be `Bound`.

## Access and verify

### Kibana

With Minikube's Docker driver on macOS, the NodePort may not be directly
reachable at `http://$(minikube ip):30601`. Use Minikube's service tunnel
instead. Keep this command running in a terminal; it prints a local URL:

```sh
minikube service kibana-service -n elk-k8s --url
```

Open the printed URL in a browser. In Kibana, create a data view matching
`springboot-*`, choose `@timestamp` as the time field, and open **Discover**.

### Elasticsearch API and indexed events

Forward Elasticsearch's internal service to localhost:

```sh
kubectl -n elk-k8s port-forward service/elasticsearch-service 9200:9200
```

In another terminal:

```sh
curl http://localhost:9200
curl 'http://localhost:9200/_cat/indices?v'
curl 'http://localhost:9200/springboot-demo/_count'
curl 'http://localhost:9200/_cluster/health'
```

The one-node cluster may report `yellow`: the default `springboot-demo` index
has a replica shard that cannot be assigned without a second Elasticsearch
node. The primary shard and ingested documents should still be available.

### Producer files, Logstash state, and logs

```sh
kubectl -n elk-k8s exec deployment/logstash-deployment -- ls -l /usr/share/logstash/logs
kubectl -n elk-k8s exec deployment/logstash-deployment -- ls -l /usr/share/logstash/data
kubectl -n elk-k8s logs -f deployment/logstash-deployment
kubectl -n elk-k8s logs -f deployment/springboot-logger
kubectl -n elk-k8s exec deployment/logstash-deployment -- curl --fail http://localhost:9600/
```

The Elasticsearch `_count` should increase as the producer emits events. The
Logstash data directory should contain `springboot-sincedb` and its persistent
queue state.

## Updating and restarting

After changing producer code, rebuild the image with the same tag and restart
the Deployment so pods use the rebuilt image:

```sh
minikube image build -t elk-springboot-logger:local ./springboot-logger
kubectl -n elk-k8s rollout restart deployment/springboot-logger
kubectl -n elk-k8s rollout status deployment/springboot-logger
```

After changing a Kubernetes YAML manifest, re-apply it and check the rollout:

```sh
kubectl apply -f k8s/<manifest>.yaml
kubectl -n elk-k8s rollout status deployment/<deployment-name>
```

Because Logstash uses a persistent data directory, retain its `Recreate`
strategy when changing the Logstash Deployment.

If Minikube's API becomes unresponsive, first check:

```sh
minikube status
kubectl get nodes
```

Restarting Minikube interrupts workloads but should preserve data on the PVCs:

```sh
minikube stop
minikube start
kubectl -n elk-k8s get pods,pvc
```

Wait for all Deployments to become Ready again. In this migration, Minikube
became temporarily unresponsive during Kibana startup; after an approved
restart, the PVCs and Elasticsearch data remained intact.

## Cleanup

Remove Deployments, Services, and the Logstash ConfigMap while retaining
persistent data:

```sh
kubectl -n elk-k8s delete deployment springboot-logger kibana-deployment logstash-deployment elasticsearch-deployment
kubectl -n elk-k8s delete service kibana-service logstash-service elasticsearch-service
kubectl -n elk-k8s delete configmap logstash-config
```

To delete the persistent data as well, delete the PVCs explicitly:

```sh
kubectl -n elk-k8s delete pvc elasticsearch-data producer-logs logstash-data
```

Delete the namespace and all remaining namespaced resources only when you want
to remove the entire Kubernetes stack:

```sh
kubectl delete namespace elk-k8s
```

Deleting a namespace also deletes its PVCs; whether the underlying volumes are
retained depends on the StorageClass reclaim policy. The Minikube `standard`
StorageClass uses reclaim policy `Delete`.

## Limitations

This is a local learning deployment, not production-ready:

- Elasticsearch security is disabled; there is no authentication or TLS.
- Elasticsearch, Logstash, and Kibana each have one replica and are single
  points of failure.
- Elasticsearch runs in single-node mode. Scaling its replica count is not
  sufficient to form a multi-node Elasticsearch cluster.
- The RWO shared log PVC depends on all pods running on the same Minikube node.
- There are no backups, resource autoscaling policies, external secrets, or
  production ingress configuration.
