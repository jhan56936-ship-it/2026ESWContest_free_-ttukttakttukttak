#!/bin/sh
# 펌웨어 단위 테스트 — 기기 없이 PC에서 바로 돌립니다.
#   사용법:  cd firmware/test && ./run_tests.sh
set -e
cd "$(dirname "$0")"
c++ -std=c++11 -Wall -Wextra -O2 -o /tmp/vkp_test test_vkp.cpp
/tmp/vkp_test
