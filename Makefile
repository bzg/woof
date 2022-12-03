.PHONY: clean-log clean-db

clean-log:
	rm -f log.txt

clean-db:
	rm -rf .db

clean: clean-log clean-db
