## Overview

OTP-ODRunner is a special version of OTP hacked to run a set of O/Ds gleaned from a CSV through the trip planner and return a set of basic metrics.

## Building The Graph

Is exactly like OTP. Use the --build and --save flags.

## Running

Use the --load flag to load the graph and run the CSV file through the system. Input file must be named OD_TEST.csv and exist in the current working directory (CWD), and have the columns as in the attached file (see test-data). Output will be placed in the CWD as "output.csv".

The "MODE" column is the OTP mode with which to run the request.

Rows that result in a TP error or no results will be skipped and not appear in the output.
