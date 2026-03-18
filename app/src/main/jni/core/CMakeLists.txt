project(core)

add_library(capture SHARED
        nemo_core.c
        capture_vpn.c
        traffic_conditioner.c
        ip_lru.c
        crc32.c
        log_writer.c
        jni_impl.c)

if(ANDROID)
        find_library(log-lib log)
        set(extra-libs ${log-lib})
else()
        set(extra-libs "pthread" "m")
endif()

target_link_libraries(capture
        zdtun
        common
        ${extra-libs})
