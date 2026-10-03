foreach(suite test_hash test_splay test_integration)
    add_executable(${suite} test_main.cpp ${suite}.cpp)
    target_link_libraries(${suite} PRIVATE delta_lib)
    add_test(NAME ${suite} COMMAND ${suite})
endforeach()

# The command-line tests run the delta executable.
add_executable(test_cli test_main.cpp test_cli.cpp)
target_link_libraries(test_cli PRIVATE delta_lib)
target_compile_definitions(test_cli PRIVATE DELTA_CLI="$<TARGET_FILE:delta>")
add_dependencies(test_cli delta)
add_test(NAME test_cli COMMAND test_cli)
