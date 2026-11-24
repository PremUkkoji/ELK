# ELK

## Run the stack on Minikube

The manifests in `k8s/` deploy resources in the `elk-k8s` namespace:

- Elasticsearch: one pod with a persistent 1 GiB volume and an internal
  ClusterIP service.
- Logstash: one pod that reads producer JSON files from the shared volume,
  keeps its sincedb and persistent queue on a separate persistent volume, and
  sends events to Elasticsearch.
- Spring Boot log producer: two pods writing separate JSON files to the shared
  volume.
- Kibana: one pod exposed through a NodePort service.

The producer image is built directly into Minikube's image store. Do not use
the Docker Desktop image store for this step:

```sh
minikube status
minikube image build -t elk-springboot-logger:local ./springboot-logger
```

Apply the manifests in dependency order:

```sh
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/elasticsearch.yaml
kubectl apply -f k8s/storage.yaml
kubectl apply -f k8s/logstash.yaml
kubectl apply -f k8s/springboot-logger.yaml
kubectl apply -f k8s/kibana.yaml
```

Wait for the workloads and inspect their persistent volume claims:

```sh
kubectl -n elk-k8s rollout status deployment/elasticsearch-deployment
kubectl -n elk-k8s rollout status deployment/logstash-deployment
kubectl -n elk-k8s rollout status deployment/springboot-logger
kubectl -n elk-k8s rollout status deployment/kibana-deployment
kubectl -n elk-k8s get pods,services,pvc
```

On macOS with Minikube's Docker driver, use the service tunnel to reach Kibana.
Run this in a terminal and keep it open while using the printed URL:

```sh
minikube service kibana-service -n elk-k8s --url
```

To query Elasticsearch from another terminal:

```sh
kubectl -n elk-k8s port-forward service/elasticsearch-service 9200:9200
curl http://localhost:9200
curl http://localhost:9200/_cat/indices?v
curl http://localhost:9200/springboot-demo/_count
```

In Kibana, create a data view matching `springboot-*`, select `@timestamp`,
and open **Discover**. To inspect the shared JSON files and Logstash state:

```sh
kubectl -n elk-k8s exec deployment/logstash-deployment -- ls -l /usr/share/logstash/logs
kubectl -n elk-k8s exec deployment/logstash-deployment -- ls -l /usr/share/logstash/data
kubectl -n elk-k8s logs -f deployment/logstash-deployment
kubectl -n elk-k8s logs -f deployment/springboot-logger
```

The producer log claim uses `ReadWriteOnce`, which works here because all pods
run on Minikube's single node. It is not suitable for spreading producers and
Logstash across multiple nodes without changing to shared storage that supports
`ReadWriteMany`. The Elasticsearch cluster may report `yellow` because this
single-node setup cannot allocate replica shards.

These manifests are for local learning, not production: Elasticsearch security
is disabled, there is no authentication or TLS, and the single Elasticsearch,
Logstash, and Kibana pods are single points of failure. Do not expose these
services to an untrusted network.

To remove the workloads but retain their data, delete the Deployments,
Services, and Logstash ConfigMap while keeping the PVCs:

```sh
kubectl -n elk-k8s delete deployment springboot-logger kibana-deployment logstash-deployment elasticsearch-deployment
kubectl -n elk-k8s delete service kibana-service logstash-service elasticsearch-service
kubectl -n elk-k8s delete configmap logstash-config
```

Delete the PVCs only when you intend to discard the Elasticsearch data, the
producer log files, and Logstash state:

```sh
kubectl -n elk-k8s delete pvc elasticsearch-data producer-logs logstash-data
```

## Start the stack with Docker compose

Start Elasticsearch, Logstash, Kibana, and two instances of the sample Spring
Boot log producer:

```sh
docker compose up --build --scale springboot-logger=2 -d
```

Each sample application writes ten structured JSON log events every ten seconds
to its own file under `./springboot-logger/logs/`, named with its container hostname. With two
instances, that is 20 events per ten-second interval and two separate files.
The events cover different types and include INFO, WARN, and ERROR levels.
Logstash reads all `*.json` files from the same shared directory and writes the
events to the `springboot-demo` index.
The host directory is mounted read-write in each producer and read-only in
Logstash; events are newline-delimited JSON.

The Logstash file input tracks its read position in the persistent
`logstash_data` volume. This prevents already-read lines from being indexed
again after a normal container restart.

Check that both producer instances are running with:

```sh
docker compose ps springboot-logger
```

Open Kibana at [http://localhost:5601](http://localhost:5601), create a data
view matching `springboot-*`, and open **Discover**. Choose `@timestamp` as the
time field and set the time range to include the incoming events.

To check the producer and Logstash:

```sh
docker compose logs -f springboot-logger logstash
```

To inspect the per-container log files on the host:

```sh
ls -l springboot-logger/logs/
tail -f springboot-logger/logs/*.json
```

Stop the stack with `docker compose down`. Elasticsearch data is retained in
the `elastic_data` volume, Logstash file-read positions are retained in
`logstash_data`, and the producer log files remain under
`./springboot-logger/logs/`.
