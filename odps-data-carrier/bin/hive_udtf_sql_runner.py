# Copyright 1999-2019 Alibaba Group Holding Ltd.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.


import os
import sys
import subprocess
import traceback
import argparse

'''
   [output directory]
   |______Report.html
   |______[database name]
          |______odps_ddl
          |      |______tables
          |      |      |______[table name].sql
          |      |______partitions
          |             |______[table name].sql
          |______hive_udtf_sql
                 |______single_partition
                 |      |______[table name].sql
                 |______multi_partition
                        |______[table name].sql
'''

temp_func_name_multi = "odps_data_dump_multi"
class_name_multi = "com.aliyun.odps.datacarrier.transfer.OdpsDataTransferUDTF"
temp_func_name_single = "odps_data_dump_single"
class_name_single = "com.aliyun.odps.datacarrier.transfer.OdpsPartitionTransferUDTF"

def execute(cmd: str, verbose=False) -> int:
  try:
    if (verbose):
      print("INFO: executing \'%s\'" % (cmd))

    sp = subprocess.Popen(cmd, shell=True, preexec_fn=os.setsid)
    sp.wait()

    return sp.returncode
  except Exception as e:
    print("ERROR: execute \'%s\'' Failed: %s" % (cmd, e))
    print(traceback.format_exc())
    return 1

def get_runnable_hive_sql(file_path: str, udtf_resource_path: str,
    odps_config_path: str) -> str:
  with open(file_path) as fd:
    hive_sql = fd.read()

  hive_sql = hive_sql.replace(
      "\n", " ")
  hive_sql = hive_sql.replace(
      "`", "")
  hive_sql = (
      "add jar %s;" % udtf_resource_path +
      "add file %s;" % odps_config_path +
      "create temporary function %s as '%s';" % (
        temp_func_name_multi, class_name_multi) +
      "create temporary function %s as '%s';" % (
        temp_func_name_single, class_name_single) +
      hive_sql)

  return hive_sql

def run_all(root: str, udtf_resource_path: str,
    odps_config_path: str) -> None:
  databases = os.listdir(root)

  for database in databases:
    if database == "report.html":
      continue

    hive_multi_partition_sql_dir = os.path.join(
        root, database, "hive_udtf_sql", "multi_partition")

    hive_multi_partition_sql_files = os.listdir(
        hive_multi_partition_sql_dir)

    for hive_multi_partition_sql_file in hive_multi_partition_sql_files:
      file_path = os.path.join(
          hive_multi_partition_sql_dir, hive_multi_partition_sql_file)

      hive_multi_partition_sql = get_runnable_hive_sql(
          file_path, udtf_resource_path, odps_config_path)

      retry = 5
      while retry > 0:
        returncode = execute(
            "hive -e \"%s\"" % hive_multi_partition_sql, verbose=True)
        if returncode == 0:
          break
        else:
          print("INFO: execute %s failed, retrying..." % file_path)
        retry -= 1

      if retry == 0:
        print("ERROR: execute %s failed 5 times" % file_path)

def run_single_file(hive_single_partition_sql_path: str,
    udtf_resource_path: str, odps_config_path: str) -> None:
  hive_single_partition_sql = get_runnable_hive_sql(
      hive_single_partition_sql_path, udtf_resource_path, odps_config_path)

  retry = 5
  while retry > 0:
    returncode = execute(
        "hive -e \"%s\"" % hive_single_partition_sql, verbose=True)
    if returncode == 0:
      break
    else:
      print("INFO: execute %s failed, retrying..." % hive_single_partition_sql_path)
    retry -= 1

  if retry == 0:
    print("ERROR: execute %s failed 5 times" % hive_single_partition_sql_path)



if __name__ == '__main__':
  parser = argparse.ArgumentParser(
      description='Run hive UDTF SQL automatically.')
  parser.add_argument(
      "--input_all",
      required=False,
      help="path to directory generated by meta processor")
  parser.add_argument(
      "--input_single_file",
      required=False,
      help="path to a single sql file")
  args = parser.parse_args()

  # Get path to udtf jar & odps config
  script_path = os.path.dirname(os.path.realpath(__file__))
  odps_data_carrier_path = os.path.dirname(script_path)
  if args.input_single_file is not None:
    args.input_single_file = os.path.abspath(args.input_single_file)
  if args.input_all is not None:
    args.input_all = os.path.abspath(args.input_all)
  os.chdir(odps_data_carrier_path)

  udtf_path = os.path.join(
      odps_data_carrier_path,
      "libs",
      "data-transfer-hive-udtf-1.0-SNAPSHOT-jar-with-dependencies.jar"
  )
  if not os.path.exists(udtf_path):
    print("ERROR: %s does not exist" % udtf_path)
    sys.exit(1)

  odps_config_path = os.path.join(
      odps_data_carrier_path,"odps_config.ini")
  if not os.path.exists(odps_config_path):
    print("ERROR: %s does not exist" % udtf_path)
    sys.exit(1)

  if args.input_single_file is not None:
    run_single_file(args.input_single_file, udtf_path, odps_config_path)
  elif args.input_all is not None:
    run_all(args.input_all, udtf_path, odps_config_path)
  else:
    print("ERROR: please specify --input_all or --input_single_file")
    sys.exit(1)
