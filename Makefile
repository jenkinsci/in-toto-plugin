.PHONY: all build docker-build
.SILENT:

all: build

build:
	mvn package

docker-build:
	bash -ec ' \
		docker build -t in-toto-jenkins:latest .; \
		docker container create --name in-toto-jenkins in-toto-jenkins:latest /bin/true; \
		docker container cp in-toto-jenkins:/in-toto.hpi .; \
		docker container rm in-toto-jenkins \
	'
