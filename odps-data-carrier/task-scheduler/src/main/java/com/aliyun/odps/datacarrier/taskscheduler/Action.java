package com.aliyun.odps.datacarrier.taskscheduler;

public enum Action {
  ODPS_DDL,
  ODPS_EXTERNAL_DDL,
  ODPS_CREATE_TABLE,
  ODPS_CREATE_EXTERNAL_TABLE,
  ODPS_ADD_PARTITION,
  ODPS_ADD_EXTERNAL_TABLE_PARTITION,
  ODPS_LOAD_DATA,
  HIVE_LOAD_DATA,
  ODPS_VALIDATE,
  HIVE_VALIDATE,
  VALIDATION_BY_TABLE,
  VALIDATION_BY_PARTITION,
  UNKNOWN
}
