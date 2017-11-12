#!/bin/bash

#RESOLV_SERVERS="nameserver 10.201.50.60"
#RESOLV_SERVERS="nameserver 127.0.1.1"

# https://wiki.archlinux.org/index.php/resolv.conf#Alternative_DNS_servers
RESOLV_SERVERS="nameserver 8.8.8.8; nameserver 8.8.4.4; nameserver 84.200.69.80; nameserver 84.200.70.40; nameserver 77.88.8.8; nameserver 77.88.8.8"

RESOLV_OPTIONS="options timeout:600; options attempts:30; options no-check-names; options rotate"

time zcat crawler_domains.txt.gz \
    | head -n 300 \
    | adnshost --fmt-asynch --asynch --cname-ok --type a ----addr-ipv4-only --config "${RESOLV_OPTIONS}; ${RESOLV_SERVERS}" --pipe --ttl-abs \
    | grep '$ "OK"' \
    | wc -l