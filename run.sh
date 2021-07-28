kubectl create namespace confluent
kubectl config set-context --current --namespace confluent

helm repo add confluentinc https://packages.confluent.io/helm
helm repo update

helm upgrade --install confluent-operator confluentinc/confluent-for-kubernetes

kubectl apply -f ksqldb.yaml

KSQL_DB_HOST=$(kubectl get service ksqldb-external -o jsonpath="{.status.loadBalancer.ingress[0].hostname}")
if [ -z "$KSQL_DB_HOST" ]; then
  KSQL_DB_HOST=$(kubectl get service ksqldb-external -o jsonpath="{.status.loadBalancer.ingress[0].ip}")
fi

while [ -z "$KSQL_DB_HOST" ]; do
  sleep 5
  KSQL_DB_HOST=$(kubectl get service ksqldb-external -o jsonpath="{.status.loadBalancer.ingress[0].hostname}")
  if [ -z "$KSQL_DB_HOST" ]; then
    KSQL_DB_HOST=$(kubectl get service ksqldb-external -o jsonpath="{.status.loadBalancer.ingress[0].ip}")
  fi
done

echo "$KSQL_DB_HOST"

export KSQL_DB_HOST

# deploy the confluent platform to the k8s
mkdir "tmp"
envsubst '$KSQL_DB_HOST' <confluent-platform.yaml >tmp/confluent-platform.yaml
kubectl apply -f tmp/confluent-platform.yaml
rm -r "tmp"

IS_NUMBER_REGEX='^[0-9]+$'

SERVICE_STATUS=$(kubectl get statefulset controlcenter -o jsonpath="{.status.readyReplicas}")
while [[ "$SERVICE_STATUS" == 0 ]] || ! [[ $SERVICE_STATUS =~ $IS_NUMBER_REGEX ]]; do
  sleep 5
  SERVICE_STATUS=$(kubectl get statefulset controlcenter -o jsonpath="{.status.readyReplicas}")
done

echo "CONTROL CENTER IS UP"

SERVICE_STATUS=$(kubectl get statefulset connect -o jsonpath="{.status.readyReplicas}")
while [[ "$SERVICE_STATUS" == 0 ]] || ! [[ $SERVICE_STATUS =~ $IS_NUMBER_REGEX ]]; do
  sleep 5
  SERVICE_STATUS=$(kubectl get statefulset connect -o jsonpath="{.status.readyReplicas}")
done

echo "CONNECT IS UP"

# create expedia source connector

CONNECT_HOST_NAME=$(kubectl get service connect-external -o jsonpath="{.status.loadBalancer.ingress[0].hostname}")
if [ -z "$CONNECT_HOST_NAME" ]; then
  CONNECT_HOST_NAME=$(kubectl get service connect-external -o jsonpath="{.status.loadBalancer.ingress[0].ip}")
fi
mkdir "tmp"
envsubst '$ACCOUNT_NAME, $ACCOUNT_KEY' <connectors/azure_source_expedia.json >tmp/azure_source_expedia.json
curl -s -X POST -H 'Content-Type: application/json' --data @tmp/azure_source_expedia.json "$CONNECT_HOST_NAME:8083/connectors"
rm -r "tmp"

# add topic expedia

kubectl apply -f expedia-topic.yaml

mkdir "tmp"
envsubst '$ARGS' <kafka-streams-deployment.yaml >tmp/kafka-streams-deployment.yaml
kubectl apply -f tmp/kafka-streams-deployment.yaml
rm -r "tmp"
