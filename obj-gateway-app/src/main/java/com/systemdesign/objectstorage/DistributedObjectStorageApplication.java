package com.systemdesign.objectstorage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EntityScan(basePackages = "com.systemdesign.objectstorage")
@EnableJpaRepositories(basePackages = "com.systemdesign.objectstorage")
public class DistributedObjectStorageApplication {
    public static void main(String[] args) {
        SpringApplication.run(DistributedObjectStorageApplication.class, args);
    }
}
