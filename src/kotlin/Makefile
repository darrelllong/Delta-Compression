KOTLINC = kotlinc
KOTLIN  = kotlin
JAR     = delta.jar

.PHONY: all test clean

all: $(JAR)

$(JAR): delta/*.kt
	$(KOTLINC) delta/*.kt -include-runtime -d $(JAR) 2>/dev/null

test: $(JAR)
	java -cp $(JAR) delta.TestDelta

clean:
	rm -f $(JAR)
