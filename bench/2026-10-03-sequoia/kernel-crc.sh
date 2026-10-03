#!/bin/bash
# Kernel tarballs (linux-5.1 -> 5.1.1, 871 MB each) on the HDD JBOD: three
# warm-cache rounds of the cleaned tree (after, a10ab04's code) and of the
# tree with CRC-64 by slicing-by-8 (crc), interleaved, then one cold-cache
# round of crc.  Prints MiB/s as pilot_lang.sh reports it, then what the C
# encoder reports for each tree.
set -u
export WORKDIR=/mnt/delta/kernel
unset PILOT_REF PILOT_VER
gov() { for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do echo "$1" | sudo tee "$f" >/dev/null; done; }
old=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor); trap 'gov "$old"' EXIT; gov performance
pin() { case $1 in Java|Go) echo 0-7;; *) echo 2;; esac; }
cat $WORKDIR/linux-5.1.tar $WORKDIR/linux-5.1.1.tar > /dev/null
for round in 1 2 3; do for l in Rust C Cpp Java Go; do for a in onepass correcting; do for t in after crc; do
  echo "warm $t $l $a $(taskset -c $(pin $l) bash /mnt/delta/$t/tests/pilot_lang.sh $l $a 2>/dev/null)"
done; done; done; done
for l in Rust C Cpp Java Go; do for a in onepass correcting; do
  sync; echo 3 | sudo tee /proc/sys/vm/drop_caches > /dev/null
  echo "cold crc $l $a $(taskset -c $(pin $l) bash /mnt/delta/crc/tests/pilot_lang.sh $l $a 2>/dev/null)"
done; done
sha256sum $WORKDIR/pilot-*.delta | cut -c1-12,65-
cat $WORKDIR/linux-5.1.tar $WORKDIR/linux-5.1.1.tar > /dev/null
for t in after crc; do for a in onepass correcting; do for i in 1 2 3; do
  s=$(date +%s.%N)
  tool=$(taskset -c 2 /mnt/delta/$t/src/c/delta encode $a $WORKDIR/linux-5.1.tar $WORKDIR/linux-5.1.1.tar $WORKDIR/c-$t.delta | awk '/^Time/{print $2}')
  e=$(date +%s.%N)
  echo "c-encoder $t $a differencing $tool command $(echo "$e - $s" | bc)s"
done; done; done
