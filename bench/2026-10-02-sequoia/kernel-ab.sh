#!/bin/bash
# Kernel tarballs (linux-5.1 -> 5.1.1, 871 MB each) on the HDD JBOD:
# three warm-cache rounds per tree, interleaved, then one cold-cache round
# of the cleaned tree.  Prints MiB/s as pilot_lang.sh reports it.
set -u
export WORKDIR=/mnt/delta/kernel
unset PILOT_REF PILOT_VER
gov() { for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do echo "$1" | sudo tee "$f" >/dev/null; done; }
old=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor); trap 'gov "$old"' EXIT; gov performance
pin() { case $1 in Java|Go) echo 0-7;; *) echo 2;; esac; }
cat $WORKDIR/linux-5.1.tar $WORKDIR/linux-5.1.1.tar > /dev/null
for round in 1 2 3; do for l in Rust C Cpp Java Go; do for a in onepass correcting; do for t in before after; do
  echo "warm $t $l $a $(taskset -c $(pin $l) bash /mnt/delta/$t/tests/pilot_lang.sh $l $a)"
done; done; done; done
for l in Rust C Cpp Java Go; do for a in onepass correcting; do
  sync; echo 3 | sudo tee /proc/sys/vm/drop_caches > /dev/null
  echo "cold after $l $a $(taskset -c $(pin $l) bash /mnt/delta/after/tests/pilot_lang.sh $l $a)"
done; done
sha256sum $WORKDIR/pilot-*.delta | cut -c1-12,65-
