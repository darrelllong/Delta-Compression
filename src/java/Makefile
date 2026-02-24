JAVAC  = /opt/homebrew/opt/openjdk@17/bin/javac
JAVA   = /opt/homebrew/opt/openjdk@17/bin/java

.PHONY: all test clean

all:
	mkdir -p out
	$(JAVAC) --release 17 -d out delta/*.java

test: all
	$(JAVA) -cp out delta.TestDelta

clean:
	rm -rf out
