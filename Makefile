# Copyright (c) 2022 Bastien Guerry <bzg@gnu.org>
# SPDX-License-Identifier: EPL-2.0
# License-Filename: LICENSES/EPL-2.0.txt

.PHONY: clean-log clean-db clean

clean-log:
	rm -f log.txt

clean-db:
	rm -rf .db

clean: clean-log clean-db
