#!/bin/bash
PROJ=/mnt/c/Users/Administrator/Downloads/DaisyDiverLoder
tr -d '\r' < $PROJ/driver/build_native_wsl.sh > /home/jubair/build_native.sh
chmod +x /home/jubair/build_native.sh
nohup bash /home/jubair/build_native.sh > /tmp/native.log 2>&1 &
echo "LAUNCHED pid=$!"
sleep 2
tail -3 /tmp/native.log
