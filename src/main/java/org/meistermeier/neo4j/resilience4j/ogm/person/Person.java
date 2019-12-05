package org.meistermeier.neo4j.resilience4j.ogm.person;

import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;

/**
 * @author Gerrit Meier
 */
@NodeEntity
public class Person {

	@Id
	private Long id;

	private String name;

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}
}
