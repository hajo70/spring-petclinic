package org.springframework.samples.petclinic.architecture;

import de.spricom.dessert.slicing.Classpath;
import de.spricom.dessert.slicing.Clazz;
import de.spricom.dessert.slicing.Root;
import de.spricom.dessert.slicing.Slice;
import org.junit.jupiter.api.Test;
import org.springframework.samples.petclinic.PetClinicApplication;

import java.util.stream.Collectors;

public class InvestigationTests {

	private final static Classpath cp = new Classpath();

	@Test
	void detectUsageOfReflection() {
		Root petClinic = cp.rootOf(PetClinicApplication.class);
		petClinic.slice(c -> c.uses(cp.slice("java.lang.reflect..*"))).getClazzes().forEach(System.out::println);
	}

	@Test
	void showReflectionUsageForSpringFramework() {
		Slice spring = cp.slice("org.springframework..*");
		Slice reflection = cp.slice("java.lang.reflect..*");
		spring.slice(c -> c.uses(reflection)).getClazzes()
				.forEach(c -> System.out.printf("%s[%s]%n", c.getName(), c.getDependencies().slice(reflection)
						.getClazzes().stream().map(Clazz::getSimpleName).collect(Collectors.joining(", "))));
	}

}
