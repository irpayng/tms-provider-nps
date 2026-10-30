# Eclipse formatter seed

`mvn spotless:check` formats with the Eclipse JDT formatter. Spotless resolves
most of it from Maven Central, but one bundle (`com.sun.jna`) and the update
site's metadata come from `download.eclipse.org`. That site went down on
2026-10-21 and served an HTML page instead, so every lint run without a warm
cache failed with `could not parse the content at .../p2.index`.

`eclipse-4.33-p2.tgz` is that part of Spotless's cache
(`~/.m2/repository/dev/equo/p2-data`, minus `queries/`, which holds absolute
paths). CI unpacks it before the formatting check, so lint never needs the site.

Rebuild it if the `<eclipse><version>` in `pom.xml` changes: run
`mvn spotless:check` once while the site is up, then

    COPYFILE_DISABLE=1 tar --no-mac-metadata --no-xattrs -czf eclipse-<version>-p2.tgz \
      -C ~/.m2/repository/dev/equo --exclude='p2-data/queries' p2-data
