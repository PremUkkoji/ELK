# ELK

## Start the stack

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
