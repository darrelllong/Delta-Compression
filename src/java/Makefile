JAVAC  = javac
JAVA   = java

.PHONY: all test clean

all:
	mkdir -p out
	$(JAVAC) -d out delta/*.java

test: all
	$(JAVA) -cp out delta.TestDelta

clean:
	rm -rf out
