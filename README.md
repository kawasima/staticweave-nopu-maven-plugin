# staticweave-nopu-maven-plugin

The staticweave-nopu-maven-plugin is a maven plugin for static-weaving Eclipselink entities without persistence.xml or orm.xml. 

## Usage

Set the target packages contains the entities.

```xml
<plugin>
  <groupId>net.unit8.maven.plugins</groupId>
  <artifactId>staticweave-nopu-maven-plugin</artifactId>
  <version>0.1.0</version>
  <configuration>
    <packages>
      <package>xxx.yyy.entity</package>
    </packages>
  </configuration>
</plugin>
```

## License

Copyright © 2019 kawasima

Distributed under the Apache License either version 2.0.