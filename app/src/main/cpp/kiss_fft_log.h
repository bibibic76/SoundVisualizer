#ifndef KISS_FFT_LOG_H
#define KISS_FFT_LOG_H

#include <stdio.h>

#define KISS_FFT_ERROR(...) fprintf(stderr, __VA_ARGS__)
#define KISS_FFT_WARNING(...) fprintf(stderr, __VA_ARGS__)
#define KISS_FFT_INFO(...) fprintf(stderr, __VA_ARGS__)
#define KISS_FFT_DEBUG(...) fprintf(stderr, __VA_ARGS__)

#endif
