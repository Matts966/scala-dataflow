run:
	mvn compile exec:java \
		-Dexec.mainClass=com.example.Example \
		-Dexec.args="--project=spa-sandbox \
--runner=DirectRunner \
--tempLocation=gs://beam_test_spa_sandbox \
--jobName=BigqueryToGCS" \
		-Pdirect-runner
.PHONY: run
