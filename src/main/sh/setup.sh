#!/usr/bin/env bash

python3.9 -m venv env
source env/bin/activate

module load gcc/9.2.0

pip install --upgrade pip
pip install optuna geopandas rtree pygeos

pip install --force-reinstall "matsim-tools[calibration] @ git+https://github.com/matsim-vsp/matsim-python-tools.git"
