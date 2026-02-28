SCALAC    = scalac
JAVA      = /opt/homebrew/opt/openjdk@17/bin/java
JAR       = delta.jar
SCALA_LIB = /opt/homebrew/opt/scala/libexec/lib/scala.jar
SRCS      = $(wildcard delta/*.scala)

.PHONY: all test clean

all: $(JAR)

$(JAR): $(SRCS)
	$(SCALAC) -d $(JAR) $(SRCS) 2>&1

test: $(JAR)
	$(JAVA) -cp $(JAR):$(SCALA_LIB) delta.TestDelta

clean:
	rm -f $(JAR)
