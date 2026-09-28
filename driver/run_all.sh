#!/bin/bash
PROJ=/mnt/c/Users/Administrator/Downloads/DaisyDiverLoder
tr -d '\r' < "$PROJ/driver/build_all_versions.sh" > /root/build_all.sh
chmod +x /root/build_all.sh
nohup bash /root/build_all.sh > /root/build_all_console.log 2>&1 &
echo "LAUNCHED pid=$!"
