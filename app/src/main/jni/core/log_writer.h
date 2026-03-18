#ifndef NEMO_LOG_WRITER_H
#define NEMO_LOG_WRITER_H

#include "common/utils.h"

#define PD_DEFAULT_LOGGER 0
#define PD_DEFAULT_LOGGER_LEVEL ANDROID_LOG_INFO

int nemo_init_logger(const char *path, int min_lvl);
int nemo_log_write(int logger, int lvl, const char *msg);
void nemo_close_loggers();

#endif // NEMO_LOG_WRITER_H
