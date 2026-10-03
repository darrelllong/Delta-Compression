#pragma once

// The overloaded-lambda idiom for std::visit.  Not installed.

namespace delta::detail {

/// Builds a visitor for std::visit from one lambda per alternative.
template <class... Fs> struct overloaded : Fs... { using Fs::operator()...; };
template <class... Fs> overloaded(Fs...) -> overloaded<Fs...>;

} // namespace delta::detail
