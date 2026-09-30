package com.fintech.cfo;

import org.springframework.boot.SpringApplication;

public class TestCfoApplication {

	public static void main(String[] args) {
		SpringApplication.from(CfoApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
