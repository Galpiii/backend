package com.github.yeonja23.springboottemplate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing
@SpringBootApplication
public class SpringBootTemplateApplication {

	public static void main(String[] args) {
		SpringApplication.run(SpringBootTemplateApplication.class, args);
	}

}
