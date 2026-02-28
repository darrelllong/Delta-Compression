#pragma once

#include <cstddef>
#include <exception>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace delta_test {

struct TestCase {
    const char* name;
    void (*fn)();
};

struct RequirementFailed : std::runtime_error {
    RequirementFailed() : std::runtime_error("requirement failed") {}
};

inline std::vector<TestCase>& registry() {
    static std::vector<TestCase> tests;
    return tests;
}

struct Registrar {
    Registrar(const char* name, void (*fn)()) {
        registry().push_back({name, fn});
    }
};

inline std::vector<std::string>& info_stack() {
    static std::vector<std::string> infos;
    return infos;
}

template <typename T>
std::string to_string(const T& value) {
    std::ostringstream out;
    out << value;
    return out.str();
}

struct ScopedInfo {
    explicit ScopedInfo(std::string message) {
        info_stack().push_back(std::move(message));
    }

    ~ScopedInfo() {
        info_stack().pop_back();
    }
};

inline std::size_t& failure_count() {
    static std::size_t count = 0;
    return count;
}

inline void report_failure(
    const char* kind,
    const char* expr,
    const char* file,
    int line,
    const std::string& detail = {}) {
    ++failure_count();
    std::cerr << file << ":" << line << ": " << kind << " failed: " << expr;
    if (!detail.empty()) {
        std::cerr << " (" << detail << ")";
    }
    if (!info_stack().empty()) {
        std::cerr << " [context: ";
        for (std::size_t i = 0; i < info_stack().size(); ++i) {
            if (i != 0) {
                std::cerr << " | ";
            }
            std::cerr << info_stack()[i];
        }
        std::cerr << "]";
    }
    std::cerr << "\n";
}

inline bool check(
    bool condition,
    const char* expr,
    const char* file,
    int line,
    bool fatal) {
    if (condition) {
        return true;
    }
    report_failure("CHECK", expr, file, line);
    if (fatal) {
        throw RequirementFailed();
    }
    return false;
}

template <typename ExceptionType, typename Fn>
bool check_throws_as(
    Fn&& fn,
    const char* expr,
    const char* expected,
    const char* file,
    int line) {
    try {
        fn();
    } catch (const ExceptionType&) {
        return true;
    } catch (const std::exception& e) {
        report_failure(
            "CHECK_THROWS_AS",
            expr,
            file,
            line,
            std::string("expected ") + expected + ", got " + e.what());
        return false;
    } catch (...) {
        report_failure(
            "CHECK_THROWS_AS",
            expr,
            file,
            line,
            std::string("expected ") + expected + ", got unknown exception");
        return false;
    }

    report_failure(
        "CHECK_THROWS_AS",
        expr,
        file,
        line,
        std::string("expected ") + expected + ", but nothing was thrown");
    return false;
}

inline int run_all_tests() {
    std::size_t passed = 0;
    for (const auto& test : registry()) {
        const std::size_t before = failure_count();
        try {
            test.fn();
        } catch (const RequirementFailed&) {
        } catch (const std::exception& e) {
            report_failure("EXCEPTION", test.name, __FILE__, __LINE__, e.what());
        } catch (...) {
            report_failure("EXCEPTION", test.name, __FILE__, __LINE__, "unknown exception");
        }

        if (failure_count() == before) {
            ++passed;
        }
    }

    std::cerr << passed << " passed, "
              << (registry().size() - passed) << " failed\n";
    return failure_count() == 0 ? 0 : 1;
}

}  // namespace delta_test

#define DELTA_TEST_JOIN_IMPL(a, b) a##b
#define DELTA_TEST_JOIN(a, b) DELTA_TEST_JOIN_IMPL(a, b)

#define TEST_CASE(name, tags) \
    static void DELTA_TEST_JOIN(delta_test_case_, __LINE__)(); \
    static ::delta_test::Registrar DELTA_TEST_JOIN(delta_test_registrar_, __LINE__)( \
        name, &DELTA_TEST_JOIN(delta_test_case_, __LINE__)); \
    static void DELTA_TEST_JOIN(delta_test_case_, __LINE__)()

#define CHECK(expr) \
    do { \
        ::delta_test::check(static_cast<bool>(expr), #expr, __FILE__, __LINE__, false); \
    } while (false)

#define CHECK_FALSE(expr) CHECK(!(expr))

#define REQUIRE(expr) \
    do { \
        ::delta_test::check(static_cast<bool>(expr), #expr, __FILE__, __LINE__, true); \
    } while (false)

#define CHECK_THROWS_AS(expr, exc_type) \
    do { \
        ::delta_test::check_throws_as<exc_type>( \
            [&]() { (void)(expr); }, #expr, #exc_type, __FILE__, __LINE__); \
    } while (false)

#define INFO(message) \
    ::delta_test::ScopedInfo DELTA_TEST_JOIN(delta_test_info_, __LINE__)( \
        ::delta_test::to_string(message))
