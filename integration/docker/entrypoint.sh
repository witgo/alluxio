#!/usr/bin/env bash
#
# The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
# (the "License"). You may not use this work except in compliance with the License, which is
# available at www.apache.org/licenses/LICENSE-2.0
#
# This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
# either express or implied, as more fully set forth in the License.
#
# See the NOTICE file distributed with this work for information regarding copyright ownership.
#

set -e

NO_FORMAT='--no-format'

function printUsage {
  echo "Usage: COMMAND [COMMAND_OPTIONS]"
  echo
  echo "COMMAND is one of:"
  echo -e " master [--no-format]    \t Start Alluxio master. If --no-format is specified, do not format"
  echo -e " worker [--no-format]    \t Start Alluxio worker. If --no-format is specified, do not format"
  echo -e " proxy                   \t Start Alluxio proxy"
}

if [[ $# -lt 1 ]]; then
  printUsage
  exit 1
fi

service=$1
options=$2

# Only set ALLUXIO_RAM_FOLDER if tiered storage isn't explicitly configured
if [[ -z "${ALLUXIO_WORKER_TIEREDSTORE_LEVEL0_DIRS_PATH}" ]]; then
  # Docker will set this tmpfs up by default. Its size is configurable through the
  # --shm-size argument to docker run
  export ALLUXIO_RAM_FOLDER=${ALLUXIO_RAM_FOLDER:-/dev/shm}
fi

home=/opt/alluxio
cd ${home}

if [ "$ENABLE_FUSE" = true ]; then
  integration/fuse/bin/alluxio-fuse mount /alluxio-fuse /
fi

case ${service,,} in
  master)
    if [[ -n ${options} && ${options} != ${NO_FORMAT} ]]; then
      printUsage
      exit 1
    fi
    if [[ ${options} != ${NO_FORMAT} ]]; then
      bin/alluxio formatMaster
    fi
    integration/docker/bin/alluxio-master.sh
    ;;
  worker)
    if [[ -n ${options} && ${options} != ${NO_FORMAT} ]]; then
      printUsage
      exit 1
    fi
    if [[ ${options} != ${NO_FORMAT} ]]; then
      bin/alluxio formatWorker
    fi
    integration/docker/bin/alluxio-worker.sh
    ;;
  proxy)
    integration/docker/bin/alluxio-proxy.sh
    ;;
  *)
    printUsage
    exit 1
    ;;
esac
