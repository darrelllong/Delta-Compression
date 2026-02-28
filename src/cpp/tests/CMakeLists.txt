add_executable(test_hash test_main.cpp test_hash.cpp)
target_link_libraries(test_hash PRIVATE delta_lib)
add_test(NAME test_hash COMMAND test_hash)

add_executable(test_integration test_main.cpp test_integration.cpp)
target_link_libraries(test_integration PRIVATE delta_lib)
add_test(NAME test_integration COMMAND test_integration)
