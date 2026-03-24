//
// Created by jason on 20/03/26.
//

#ifndef TESTAPPLICATION_NATIVE_TRACE_H
#define TESTAPPLICATION_NATIVE_TRACE_H

#define DEBUG

#ifdef DEBUG

void * trace_begin_section_wrapper (const char * section_name);

void * trace_end_section_wrapper (void);

class ScopedTrace {
public:
    inline ScopedTrace(const char * name) {
        trace_begin_section_wrapper(name);
    }

    inline ~ScopedTrace() {
        trace_end_section_wrapper();
    }
};

#define __TRACE_FUNC_WITH_NAME(name) ScopedTrace ___tracer(name)

#define TRACE_FUNC() __TRACE_FUNC_WITH_NAME(__FUNCTION__)
#define TRACE_FUNC_WITH_NAME(name) __TRACE_FUNC_WITH_NAME(name)

#define TRACE_BEGIN(section_name) trace_begin_section_wrapper(section_name)
#define TRACE_END() trace_end_section_wrapper()

#else

#define TRACE_BEGIN(section_name)
#define TRACE_END()


#define TRACE_FUNC()
#define TRACE_FUNC_WITH_NAME(name)

#endif

#endif //TESTAPPLICATION_NATIVE_TRACE_H
