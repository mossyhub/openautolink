idf_component_register(
    SRCS
        "main.c"
        "hfp_client.c"
        "audio_relay.c"
        "transport_wifi.c"
        "provisioning.c"
    INCLUDE_DIRS "."
    REQUIRES
        bt
        nvs_flash
        esp_wifi
        esp_event
        esp_netif
        lwip
        mdns
        driver
)
