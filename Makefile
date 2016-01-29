all:
	@clear
	@echo "compiling"
	@javac -cp lib/Sepia.jar src/edu/cwru/sepia/agent/AstarAgent.java

run:
	@echo "running"
	@java -cp lib/Sepia.jar:src edu.cwru.sepia.Main2 data/maze_16x16_config.xml
