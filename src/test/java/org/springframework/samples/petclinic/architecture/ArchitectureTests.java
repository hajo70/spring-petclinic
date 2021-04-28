package org.springframework.samples.petclinic.architecture;

import de.spricom.dessert.partitioning.ClazzPredicates;
import de.spricom.dessert.slicing.*;
import de.spricom.dessert.util.Predicate;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.support.SortDefinition;
import org.springframework.boot.autoconfigure.cache.JCacheManagerCustomizer;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.style.ToStringCreator;
import org.springframework.format.Formatter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.samples.petclinic.PetClinicApplication;
import org.springframework.util.StringUtils;
import org.springframework.validation.Validator;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

import javax.persistence.Entity;
import javax.persistence.MappedSuperclass;
import javax.validation.Valid;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static de.spricom.dessert.assertions.SliceAssertions.dessert;
import static org.assertj.core.api.Assertions.assertThat;

public class ArchitectureTests {

	private final static Classpath cp = new Classpath();

	@Test
	void detectUsageOfInternalApis() {
		Slice myCompanyCode = cp.slice("org.springframework.samples.petclinic..*");
		Slice jdkInternalApis = cp.slice("sun..*").plus(cp.slice("com.sun..*"));
		Slice otherInternalApis = cp.slice("..internal..*");

		dessert(myCompanyCode).usesNot(jdkInternalApis, otherInternalApis);
		assertThat(otherInternalApis.getClazzes()).isNotEmpty();
	}

	@Test
	void detectDuplicates() {
		Slice duplicates = cp.duplicates().minus("module-info");
		Map<String, List<Clazz>> duplicatesByName = duplicates.getClazzes().stream()
				.collect(Collectors.groupingBy(Clazz::getName));
		for (List<Clazz> list : duplicatesByName.values()) {
			System.out.printf("%s: %s %s%n",
					list.stream().map(Clazz::getRootFile).map(File::getName).collect(Collectors.joining(", ")),
					list.get(0).getName(), areBinaryEqual(list) ? "are binary equal" : "have binary differences");
		}
	}

	private boolean areBinaryEqual(List<Clazz> list) {
		byte[] first = getBytes(list.get(0));
		for (int i = 1; i < list.size(); i++) {
			if (!Arrays.equals(first, getBytes(list.get(i)))) {
				return false;
			}
		}
		return true;
	}

	private byte[] getBytes(Clazz clazz) {
		try (InputStream is = clazz.getURI().toURL().openStream()) {
			ByteArrayOutputStream os = new ByteArrayOutputStream();
			int c;
			while ((c = is.read()) != -1) {
				os.write(c);
			}
			os.flush();
			return os.toByteArray();
		}
		catch (IOException ex) {
			throw new IllegalStateException("Reading form " + clazz.getURI() + " failed", ex);
		}
	}

	@Test
	void detectPackageCycles() {
		Root petclinic = cp.rootOf(PetClinicApplication.class);
		dessert(petclinic.partitionByPackage()).isCycleFree();
	}

	/**
	 * Dessert operates on .class files, hence it shows dependencies introduced during
	 * bytecode enhancement.
	 */
	@Test
	void investigateUsageOfReflection() {
		Root petclinic = cp.rootOf(PetClinicApplication.class);
		Slice reflection = cp.slice("java.lang.reflect|invoke..*");
		Slice matches = petclinic.slice(c -> c.uses(reflection));
		matches.getClazzes().forEach(System.out::println);
	}

	/**
	 * Simulates these refactorings:
	 * <ol>
	 * <li>Move all <i>Pet*</i> classes from the <i>owner</i> package to a new <i>pet</i>
	 * package.</li>
	 * <li>Move the <i>VisitController</i> from the <i>owner</i> package to a new
	 * <i>pet</i> package so that only <i>Owner*</i> classes remain in the <i>owner</i>
	 * package.</li>
	 * </ol>
	 * This introduces a cycle, because the VisitController depends on Pet and
	 * PetRespository and Pet depends on Visit.
	 */
	@Disabled("Will fail, because refactoring introduces cycle.")
	@Test
	void simulateRefactoring() {
		Root petclinic = cp.rootOf(PetClinicApplication.class);
		SortedMap<String, PackageSlice> packages = petclinic.partitionByPackage();

		String pre = "org.springframework.samples.petclinic.";
		Slice owner = packages.get(pre + "owner");
		Slice visit = packages.get(pre + "visit");

		Slice pet = owner.slice("..Pet*").named(pre + "pet");
		visit = visit.plus(owner.slice("..Visit*")).named(pre + "visit");
		owner = owner.minus(pet, visit).named(pre + "owner");

		Map<String, Slice> refactored = new TreeMap<>(packages);
		Stream.of(pet, visit, owner).forEach(s -> refactored.put(s.toString(), s));

		refactored.forEach((k, s) -> s.getClazzes().forEach(c -> System.out.printf("%s.%s%n", k, c.getSimpleName())));

		dessert(refactored).isCycleFree();
	}

	@Test
	void checkLayers() {
		Root petclinic = cp.rootOf(PetClinicApplication.class);

		Slice base = petclinic.slice("..model|system..*");
		Slice logic = petclinic.slice("..owner|vet|visit..*");
		Slice application = petclinic.slice("..petclinic.*");

		assertThat(petclinic.minus(logic, base, application).getClazzes()).isEmpty();
		dessert(application, logic, base).isLayeredStrict();
	}

	@Test
	void checkModules() {
		Root petclinic = cp.rootOf(PetClinicApplication.class);

		// modules
		Slice base = petclinic.slice("..model|system..*");
		Slice owners = petclinic.slice("..owner|visit..*");
		Slice vets = petclinic.slice("..vet..*");
		Slice application = petclinic.slice("..petclinic.*");

		// external dependencies
		Slice javaBase = cp.slice("java.lang|io|util|time|text.*");
		Slice jpa = cp.slice("javax.persistence.*");
		Slice validation = cp.slice("javax.validation.constraints.*").plus(cp.sliceOf(Valid.class));
		Slice jaxb = cp.packageOf(XmlElement.class);
		Slice cache = cp.slice("java.lang.invoke.*").plus(cp.slice("javax.cache..*"))
				.plus(cp.sliceOf(JCacheManagerCustomizer.class));
		Slice springContext = cp.rootOf(Configuration.class).plus(cp.sliceOf(Autowired.class));
		Slice springSupport = cp.packageTreeOf(SortDefinition.class)
				.plus(cp.sliceOf(ToStringCreator.class, StringUtils.class));
		Slice springWeb = cp.rootOf(RequestMapping.class).plus(cp.rootOf(ModelAndView.class));
		Slice springData = cp.slice("org.springframework.data|dao|transaction..*");
		Slice springBoot = cp.slice("org.springframework.boot..*");

		// module dependencies
		dessert(base).usesOnly(javaBase, jpa, validation, springContext, springWeb, cache);
		dessert(owners).usesOnly(base, javaBase, jpa, validation, springContext, springSupport, springWeb, springData);
		dessert(vets).usesOnly(base, javaBase, jpa, jaxb, springContext, springSupport, springWeb, springData);
		dessert(application).usesOnly(base, javaBase, springContext, springBoot);
	}

	@Disabled("todo")
	@Test
	void checkTechnicalModules() {
		Root petclinic = cp.rootOf(PetClinicApplication.class);

		// modules
		Slice model = petclinic.slice(annotatedBy(Entity.class, MappedSuperclass.class, XmlRootElement.class));
		Slice infrastructure = petclinic.slice("..*Configuration");
		Slice repository = petclinic.slice("..*Repository");
		Slice presentation = Slices.of(petclinic.slice("..*Controller"),
				petclinic.slice(ClazzPredicates.implementsInterface(Formatter.class.getName())),
				petclinic.slice(ClazzPredicates.implementsInterface(Validator.class.getName())));
		Slice application = petclinic.slice("..petclinic.*");

		// external dependencies
		Slice javaBase = cp.slice("java.lang|io|util|time|text.*");
		Slice jpa = cp.slice("javax.persistence.*");
		Slice validation = cp.slice("javax.validation.constraints.*").plus(cp.sliceOf(Valid.class));
		Slice jaxb = cp.packageOf(XmlElement.class);
		Slice cache = cp.slice("java.lang.invoke.*").plus(cp.slice("javax.cache..*"))
				.plus(cp.sliceOf(JCacheManagerCustomizer.class));
		Slice springContext = cp.rootOf(Configuration.class).plus(cp.sliceOf(Autowired.class));
		Slice springSupport = cp.packageTreeOf(SortDefinition.class)
				.plus(cp.sliceOf(ToStringCreator.class, StringUtils.class));
		Slice springWeb = cp.rootOf(RequestMapping.class).plus(cp.rootOf(ModelAndView.class));
		Slice springData = cp.slice("org.springframework.data|dao|transaction..*");
		Slice springBoot = cp.slice("org.springframework.boot..*");

		// module dependencies
		dessert(model).usesOnly(javaBase, jpa, jaxb, validation, springSupport, cp.sliceOf(DateTimeFormat.class));
		dessert(infrastructure).usesOnly(javaBase, springContext, cache);
		dessert(repository).usesOnly(model, javaBase, jpa, springData, cp.sliceOf(Cacheable.class));
		dessert(presentation).usesOnly(model, repository, javaBase, validation, springContext, springSupport,
				springWeb);
		dessert(application).usesOnly(infrastructure, javaBase, springContext, springBoot);

		// consistency checks
		List<Slice> all = Arrays.asList(application, model, repository, presentation, infrastructure);

		// each class must belongs to a module
		assertThat(petclinic.minus(all).getClazzes()).isEmpty();

		// modules must be distict
		assertThat(petclinic.getClazzes())
				.hasSize(all.stream().map(Slice::getClazzes).collect(Collectors.summingInt(Collection::size)));
	}

	private Predicate<Clazz> annotatedBy(Class<? extends Annotation>... annotations) {
		return clazz -> {
			for (Class<? extends Annotation> annotation : annotations) {
				if (clazz.getClassImpl().getAnnotation(annotation) != null) {
					return true;
				}
			}
			return false;
		};
	}

}
