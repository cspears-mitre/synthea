package org.mitre.synthea.world.agents;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.google.gson.internal.LinkedTreeMap;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.mitre.synthea.helpers.Config;
import org.mitre.synthea.helpers.RandomNumberGenerator;
import org.mitre.synthea.helpers.SimpleCSV;
import org.mitre.synthea.helpers.Utilities;
import org.mitre.synthea.world.agents.behaviors.IProviderFinder;
import org.mitre.synthea.world.agents.behaviors.ProviderFinderNearest;
import org.mitre.synthea.world.agents.behaviors.ProviderFinderQuality;
import org.mitre.synthea.world.agents.behaviors.ProviderFinderRandom;
import org.mitre.synthea.world.concepts.ClinicianSpecialty;
import org.mitre.synthea.world.concepts.HealthRecord.EncounterType;
import org.mitre.synthea.world.concepts.Names;
import org.mitre.synthea.world.geography.Demographics;
import org.mitre.synthea.world.geography.Location;
import org.mitre.synthea.world.geography.quadtree.QuadTree;
import org.mitre.synthea.world.geography.quadtree.QuadTreeElement;

public class Provider implements QuadTreeElement, Serializable {

  public enum ProviderType {
    DIALYSIS, HOME_HEALTH, HOSPICE, HOSPITAL, LONG_TERM,
    NURSING, PRIMARY, REHAB, URGENT, VETERAN, PHARMACY, IHS;
  }

  public static final String ENCOUNTERS = "encounters";
  public static final String PROCEDURES = "procedures";
  public static final String LABS = "labs";
  public static final String PRESCRIPTIONS = "prescriptions";

  // Provider Selection Behavior algorithm choices:
  public static final String NEAREST = "nearest";
  public static final String QUALITY = "quality";
  public static final String RANDOM = "random";
  public static final String NETWORK = "network";

  // ArrayList of all providers imported
  private static ArrayList<Provider> providerList = new ArrayList<Provider>();
  private static QuadTree providerMap = generateQuadTree();
  private static Set<String> statesLoaded = new HashSet<String>();
  private static int loaded = 0;

  private static final double MAX_PROVIDER_SEARCH_DISTANCE =
      Config.getAsDouble("generate.providers.maximum_search_distance", 2);
  public static final String PROVIDER_SELECTION_BEHAVIOR =
      Config.get("generate.providers.selection_behavior", "nearest").toLowerCase();
  private static IProviderFinder providerFinder = buildProviderFinder();
  public static final Boolean USE_HOSPITAL_AS_DEFAULT =
      Config.getAsBoolean("generate.providers.default_to_hospital_on_failure", true);

  public Map<String, Object> attributes;
  public String uuid;
  private String locationUuid;
  public String id;
  public String npi;
  public String name;
  private Location location;
  public String address;
  public String city;
  public String state;
  public String zip;
  public String phone;
  public String rawType;
  public ProviderType type;
  public String ownership;
  /** institutional (e.g. hospital) else professional (e.g. PCP) */
  public boolean institutional;
  public int quality;
  private double revenue;
  private Point2D.Double coordinates;
  public ArrayList<EncounterType> servicesProvided;
  public Map<String, ArrayList<Clinician>> clinicianMap;
  // row: year, column: type, value: count
  private transient Table<Integer, String, AtomicInteger> utilization;

  /**
   * Java Serialization support for the utilization field.
   * @param oos stream to write to
   */
  private void writeObject(ObjectOutputStream oos) throws IOException {
    oos.defaultWriteObject();
    ArrayList<Payer.UtilizationBean> entryUtilizationElements = null;
    if (utilization != null) {
      entryUtilizationElements = new ArrayList<>(utilization.size());
      for (Table.Cell<Integer, String, AtomicInteger> cell: utilization.cellSet()) {
        entryUtilizationElements.add(
                new Payer.UtilizationBean(cell.getRowKey(), cell.getColumnKey(), cell.getValue()));
      }
    }
    oos.writeObject(entryUtilizationElements);
  }

  /**
   * Java Serialization support for the utilization field.
   * @param ois stream to read from
   */
  private void readObject(ObjectInputStream ois) throws ClassNotFoundException, IOException {
    ois.defaultReadObject();
    ArrayList<Payer.UtilizationBean> entryUtilizationElements =
            (ArrayList<Payer.UtilizationBean>)ois.readObject();
    if (entryUtilizationElements != null) {
      this.utilization = HashBasedTable.create();
      for (Payer.UtilizationBean u: entryUtilizationElements) {
        this.utilization.put(u.year, u.type, u.count);
      }
    }
  }

  /**
   * Create a new Provider with no information.
   */
  public Provider() {
    // the uuid fields are reinitialized by csvLineToProvider
    uuid = UUID.randomUUID().toString();
    locationUuid = UUID.randomUUID().toString();
    attributes = new LinkedTreeMap<>();
    revenue = 0.0;
    utilization = HashBasedTable.create();
    servicesProvided = new ArrayList<EncounterType>();
    clinicianMap = new HashMap<String, ArrayList<Clinician>>();
    coordinates = new Point2D.Double();
  }

  private static IProviderFinder buildProviderFinder() {
    IProviderFinder finder = null;
    String behavior =
        Config.get("generate.providers.selection_behavior", "nearest").toLowerCase();
    switch (behavior) {
      case QUALITY:
        finder = new ProviderFinderQuality();
        break;
      case RANDOM:
      case NETWORK:
        finder = new ProviderFinderRandom();
        break;
      case NEAREST:
      default:
        finder = new ProviderFinderNearest();
        break;
    }
    return finder;
  }

  public String getResourceID() {
    return uuid;
  }

  public String getResourceLocationID() {
    return locationUuid;
  }

  public Map<String, Object> getAttributes() {
    return attributes;
  }

  public boolean hasService(EncounterType service) {
    return servicesProvided.contains(service);
  }

  public void incrementEncounters(EncounterType service, int year) {
    increment(year, ENCOUNTERS);
    increment(year, ENCOUNTERS + "-" + service);
  }

  public void incrementProcedures(int year) {
    increment(year, PROCEDURES);
  }

  public void incrementLabs(int year) {
    increment(year, LABS);
  }

  public void incrementPrescriptions(int year) {
    increment(year, PRESCRIPTIONS);
  }

  private synchronized void increment(Integer year, String key) {
    if (utilization != null) { // TODO remove once utilization stats are made serializable
      if (!utilization.contains(year, key)) {
        utilization.put(year, key, new AtomicInteger(0));
      }

      utilization.get(year, key).incrementAndGet();
    }
  }

  public Table<Integer, String, AtomicInteger> getUtilization() {
    return utilization;
  }

  /**
   * Get the bed count for this Provider facility.
   * @return The number of beds, if they exist, otherwise null.
   */
  public Integer getBedCount() {
    if (attributes.containsKey("bed_count")) {
      return Integer.parseInt(attributes.get("bed_count").toString());
    } else {
      return null;
    }
  }

  /**
   * Will this provider accept the given person as a patient at the given time?.
   * @param person Person to consider
   * @param time Time the person seeks care
   * @return whether or not the person can receive care by this provider
   */
  public boolean accepts(Person person, long time) {
    // for now assume every provider accepts every patient
    // UNLESS it's a VA facility and the person is not a veteran or
    // it's an IHS Facility and only accepts people with a race set to Native American
    // eventually we may want to expand this (ex. capacity?)
    if (this.type == null) {
      return true;
    }
    switch (this.type) {
      case VETERAN:
        return person.attributes.containsKey(Person.VETERAN);
      case IHS:
        return "native".equals(person.attributes.get(Person.RACE));
      default:
        return true;
    }
  }

  /**
   * Adds the given amount to the provider's total revenue.
   *
   * @param costOfCare the cost of the care to be added to revenue.
   */
  public void addRevenue(double costOfCare) {
    this.revenue += costOfCare;
  }

  /**
   * Returns the total revenue of this provider.
   */
  public double getRevenue() {
    return this.revenue;
  }

  /**
   * Find specific service provider for the given person.
   * @param person The patient who requires the service.
   * @param service The service required. For example, EncounterType.AMBULATORY.
   * @param time The date/time within the simulated world, in milliseconds.
   * @return Service provider or null if none is available.
   */
  public static Provider findService(Person person, EncounterType service, long time) {
    double maxDistance = MAX_PROVIDER_SEARCH_DISTANCE;
    double degrees = 0.125;
    List<Provider> options = null;
    Provider provider = null;
    while (degrees <= maxDistance) {
      options = findProvidersByLocation(person, degrees);
      provider = providerFinder.find(options, person, service, time);
      if (provider != null) {
        return provider;
      }
      degrees *= 2.0;
    }
    return null;
  }

  /**
   * Find a service around a given point.
   * @param person The patient who requires the service.
   * @param distance in degrees
   * @return List of providers within the given distance.
   */
  private static List<Provider> findProvidersByLocation(Person person, double distance) {
    List<QuadTreeElement> results = providerMap.query(person, distance);
    List<Provider> providers = new ArrayList<Provider>();
    for (QuadTreeElement item : results) {
      providers.add((Provider) item);
    }
    return providers;
  }

  /**
   * Clear the list of loaded and cached providers.
   */
  public static void clear() {
    providerList.clear();
    statesLoaded.clear();
    providerMap = generateQuadTree();
    providerFinder = buildProviderFinder();
    loaded = 0;
  }

  /**
   * Generate a quad tree with sufficient capacity and depth to load
   * the biggest states.
   * @return QuadTree.
   */
  private static QuadTree generateQuadTree() {
    return new QuadTree();
  }

  /**
   * Load into cache the list of providers for a state.
   * @param location the state being loaded.
   */
  public static void loadProviders(Location location, long clinicianSeed) {
    if (!statesLoaded.contains(location.state)
        || !statesLoaded.contains(Location.getAbbreviation(location.state))
        || !statesLoaded.contains(Location.getStateName(location.state))) {
      try {
        Set<EncounterType> servicesProvided = new HashSet<EncounterType>();
        servicesProvided.add(EncounterType.AMBULATORY);
        servicesProvided.add(EncounterType.OUTPATIENT);
        servicesProvided.add(EncounterType.INPATIENT);

        String hospitalFile = Config.get("generate.providers.hospitals.default_file");
        loadProviders(location, hospitalFile, ProviderType.HOSPITAL, servicesProvided,
                clinicianSeed, false);

        String ihsHospitalFile = Config.get("generate.providers.ihs.hospitals.default_file");
        loadProviders(location, ihsHospitalFile, ProviderType.IHS, servicesProvided,
                clinicianSeed, true);

        servicesProvided.add(EncounterType.WELLNESS);
        String vaFile = Config.get("generate.providers.veterans.default_file");
        loadProviders(location, vaFile, ProviderType.VETERAN, servicesProvided, clinicianSeed,
                false);

        servicesProvided.clear();
        servicesProvided.add(EncounterType.WELLNESS);
        String primaryCareFile = Config.get("generate.providers.primarycare.default_file");
        loadProviders(location, primaryCareFile, ProviderType.PRIMARY, servicesProvided,
                clinicianSeed, false);
        String ihsPCFile = Config.get("generate.providers.ihs.primarycare.default_file");
        loadProviders(location, ihsPCFile, ProviderType.IHS, servicesProvided, clinicianSeed, true);

        servicesProvided.clear();
        servicesProvided.add(EncounterType.URGENTCARE);
        String urgentcareFile = Config.get("generate.providers.urgentcare.default_file");
        loadProviders(location, urgentcareFile, ProviderType.URGENT, servicesProvided,
                clinicianSeed, false);

        statesLoaded.add(location.state);
        statesLoaded.add(Location.getAbbreviation(location.state));
        statesLoaded.add(Location.getStateName(location.state));
      } catch (IOException e) {
        System.err.println("ERROR: unable to load providers for state: " + location.state);
        e.printStackTrace();
      }

      // Additional types of optional facilities
      try {
        Set<EncounterType> servicesProvided = new HashSet<EncounterType>();
        servicesProvided.clear();
        servicesProvided.add(EncounterType.HOME);
        String homeHealthFile = Config.get("generate.providers.homehealth.default_file");
        loadProviders(location, homeHealthFile, ProviderType.HOME_HEALTH, servicesProvided,
                clinicianSeed, true);

        servicesProvided.clear();
        servicesProvided.add(EncounterType.HOSPICE);
        String hospiceFile = Config.get("generate.providers.hospice.default_file");
        loadProviders(location, hospiceFile, ProviderType.HOSPICE, servicesProvided,
                clinicianSeed, true);

        servicesProvided.clear();
        servicesProvided.add(EncounterType.SNF);
        String nursingFile = Config.get("generate.providers.nursing.default_file");
        loadProviders(location, nursingFile, ProviderType.NURSING, servicesProvided,
                clinicianSeed, true);
      } catch (IOException e) {
        System.err.println("WARNING: unable to load optional providers in: " + location.state);
      }
    }
  }

  /**
   * Read the providers from the given resource file, only importing the ones for the given state.
   * THIS method is for loading providers and generating clinicians with specific specialties
   *
   * @param location the state being loaded
   * @param filename Location of the file, relative to src/main/resources
   * @param providerType ProviderType
   * @param servicesProvided Set of services provided by these facilities
   * @param clinicianSeed random seed for clinicians
   * @param optional if true the function will silently ignore a null or empty filename
   * @throws IOException if the file cannot be read
   */
  public static void loadProviders(Location location, String filename,
      ProviderType providerType, Set<EncounterType> servicesProvided, long clinicianSeed,
      boolean optional)
      throws IOException {
    if (optional && (filename == null || filename.length() == 0)) {
      return;
    }

    String resource = Utilities.readResource(filename);
    Iterator<? extends Map<String,String>> csv = SimpleCSV.parseLineByLine(resource);
    Random clinicianRand = new Random(clinicianSeed);

    while (csv.hasNext()) {
      Map<String,String> row = csv.next();
      String currState = row.get("state");
      String abbreviation = Location.getAbbreviation(location.state);

      // for now, only allow one state at a time
      if ((location.state == null)
          || (location.state != null && location.state.equalsIgnoreCase(currState))
          || (abbreviation != null && abbreviation.equalsIgnoreCase(currState))) {

        Provider parsed = csvLineToProvider(row);
        parsed.type = providerType;
        parsed.institutional =
            (providerType == ProviderType.HOSPITAL || providerType == ProviderType.NURSING);
        parsed.servicesProvided.addAll(servicesProvided);

        if ("Yes".equals(row.remove("emergency"))) {
          parsed.servicesProvided.add(EncounterType.EMERGENCY);
        }

        // add any remaining columns we didn't explicitly map to first-class fields
        // into the attributes table
        for (Map.Entry<String, String> e : row.entrySet()) {
          parsed.attributes.put(e.getKey(), e.getValue());
        }

        parsed.location = location;
        // String city = parsed.city;
        // String address = parsed.address;

        if (row.get("hasSpecialties") == null
            || row.get("hasSpecialties").equalsIgnoreCase("false")) {
          parsed.clinicianMap.put(ClinicianSpecialty.GENERAL_PRACTICE,
              parsed.generateClinicianList(1, ClinicianSpecialty.GENERAL_PRACTICE,
                  clinicianSeed, clinicianRand));
        } else {
          for (String specialty : ClinicianSpecialty.getSpecialties()) {
            String specialtyCount = row.get(specialty);
            if (specialtyCount != null && !specialtyCount.trim().equals("")
                && !specialtyCount.trim().equals("0")) {
              parsed.clinicianMap.put(specialty,
                  parsed.generateClinicianList(Integer.parseInt(row.get(specialty)), specialty,
                      clinicianSeed, clinicianRand));
            }
          }
          if (row.get(ClinicianSpecialty.GENERAL_PRACTICE).equals("0")) {
            parsed.clinicianMap.put(ClinicianSpecialty.GENERAL_PRACTICE,
                parsed.generateClinicianList(1, ClinicianSpecialty.GENERAL_PRACTICE,
                    clinicianSeed, clinicianRand));
          }
        }

        providerList.add(parsed);
        boolean inserted = providerMap.insert(parsed);
        if (!inserted) {
          throw new RuntimeException("Provider QuadTree Full! Dropping # " + loaded + ": "
              + parsed.name + " @ " + parsed.city);
        } else {
          loaded++;
        }
      }
    }
  }

  /**
   * Generates a list of clinicians, given the number to generate and the specialty.
   * @param numClinicians - the number of clinicians to generate
   * @param specialty - which specialty clinicians to generate
   * @return
   */
  private ArrayList<Clinician> generateClinicianList(int numClinicians, String specialty,
      long clinicianSeed, Random clinicianRand) {
    ArrayList<Clinician> clinicians = new ArrayList<Clinician>();
    for (int i = 0; i < numClinicians; i++) {
      Clinician clinician = null;
      clinician = generateClinician(clinicianSeed, clinicianRand,
          Long.parseLong(loaded + "" + i));
      clinician.attributes.put(Clinician.SPECIALTY, specialty);
      clinicians.add(clinician);
    }
    return clinicians;
  }

  /**
   * Generate a random clinician, from the given seed.
   *
   * @param clinicianSeed
   *          Seed for the random clinician
   * @return generated Clinician
   */
  private Clinician generateClinician(long clinicianSeed, Random clinicianRand,
      long clinicianIdentifier) {
    Clinician clinician = null;
    try {
      Person doc = new Person(clinicianIdentifier);
      Demographics cityDemographics = location.randomCity(doc);
      Map<String, Object> out = new HashMap<>();

      String race = cityDemographics.pickRace(clinicianRand);
      out.put(Person.RACE, race);
      String ethnicity = cityDemographics.pickEthnicity(clinicianRand);
      out.put(Person.ETHNICITY, ethnicity);
      String language = cityDemographics.languageFromRaceAndEthnicity(race, ethnicity,
              clinicianRand);
      out.put(Person.FIRST_LANGUAGE, language);
      String gender = cityDemographics.pickGender(clinicianRand);
      if (gender.equalsIgnoreCase("male") || gender.equalsIgnoreCase("M")) {
        gender = "M";
      } else {
        gender = "F";
      }
      out.put(Person.GENDER, gender);

      clinician = new Clinician(clinicianSeed, clinicianRand, clinicianIdentifier, this);
      clinician.attributes.putAll(out);
      clinician.attributes.put(Person.ADDRESS, address);
      clinician.attributes.put(Person.CITY, city);
      clinician.attributes.put(Person.STATE, state);
      clinician.attributes.put(Person.ZIP, zip);
      clinician.attributes.put(Person.COORDINATE, coordinates);

      String firstName = Names.fakeFirstName(gender, language, doc);
      String lastName = Names.fakeLastName(language, doc);
      clinician.attributes.put(Clinician.FIRST_NAME, firstName);
      clinician.attributes.put(Clinician.LAST_NAME, lastName);
      clinician.attributes.put(Clinician.NAME, firstName + " " + lastName);
      clinician.attributes.put(Clinician.NAME_PREFIX, "Dr.");
      // Degree's beyond a bachelors degree are not currently tracked.
      clinician.attributes.put(Clinician.EDUCATION, "bs_degree");
      String ssn = "999-" + ((doc.randInt(99 - 10 + 1) + 10)) + "-"
          + ((doc.randInt(9999 - 1000 + 1) + 1000));
      clinician.attributes.put(Person.IDENTIFIER_SSN, ssn);
    } catch (Throwable e) {
      e.printStackTrace();
      throw e;
    }
    return clinician;
  }

  /**
   * Randomly chooses a clinician out of a given clinician list.
   * @param specialty - the specialty to choose from.
   * @param rand - random number generator.
   * @return A clinician with the required specialty.
   */
  public Clinician chooseClinicianList(String specialty, RandomNumberGenerator rand) {
    ArrayList<Clinician> clinicians = this.clinicianMap.get(specialty);
    Clinician doc = clinicians.get(rand.randInt(clinicians.size()));
    doc.incrementEncounters();
    return doc;
  }

  private static String toProviderNPI(String idStr, long defaultId) {
    long id = defaultId;
    try {
      id = Long.parseLong(idStr);
    } catch (NumberFormatException e) {
      // ignore, use default value instead
    }
    if (id > 888_888_888L) {
      throw new IllegalArgumentException(
              String.format("Supplied id (%d) is too big, max is %d", id, 888_888_888L));
    }
    return toNPI(888_888_888L - id);
  }

  /**
   * Creates an NPI from a number by appending a check digit calculated according to:
   * https://www.cms.gov/Regulations-and-Guidance/Administrative-Simplification/NationalProvIdentStand/Downloads/NPIcheckdigit.pdf
   * @param id must be a 9 digit number otherwise throws an IllegalArgumentException
   * @return the NPI as a String
   */
  static String toNPI(long id) {
    if (id < 100_000_000L || id > 999_999_999L) {
      throw new IllegalArgumentException(
              String.format("Supplied identifier (%d) should be exactly 9 digits", id));
    }
    long checkDigit = 24;
    long remainingDigits = id;
    long npiWithoutCheckDigit = remainingDigits;
    boolean even = true;
    while (remainingDigits > 0) {
      long digit = remainingDigits % 10;
      if (even) {
        digit = digit * 2;
      }
      checkDigit += digit % 10;
      if (digit >= 10) {
        checkDigit++;
      }
      remainingDigits /= 10;
      even ^= true;
    }
    if ((checkDigit % 10) == 0) {
      checkDigit = 0;
    } else {
      checkDigit = (((checkDigit / 10) + 1) * 10) - checkDigit; // e.g. 67 -> 70 - 67 -> 3
    }
    return Long.toString(npiWithoutCheckDigit * 10 + checkDigit);
  }

  /**
   * Given a line of parsed CSV input, convert the data into a Provider.
   * @param line - read a csv line to a provider's attributes
   * @return A provider.
   */
  private static Provider csvLineToProvider(Map<String,String> line) {
    Provider d = new Provider();
    // using remove instead of get here so that we can iterate over the remaining keys later
    d.id = line.remove("id");
    d.npi = toProviderNPI(d.id, loaded);
    d.name = line.remove("name");
    if (d.name == null || d.name.isEmpty()) {
      d.name = d.id;
    }
    String base = d.id + d.name;
    d.uuid = UUID.nameUUIDFromBytes(base.getBytes()).toString();
    d.locationUuid = UUID.nameUUIDFromBytes(
            new StringBuilder(base).reverse().toString().getBytes()).toString();
    d.address = line.remove("address");
    d.city = line.remove("city");
    d.state = line.remove("state");
    d.zip = line.remove("zip");
    d.phone = line.remove("phone");
    d.rawType = line.remove("type");
    d.ownership = line.remove("ownership");
    try {
      d.quality = Integer.parseInt(line.remove("quality"));
    } catch (Exception e) {
      // Swallow invalid format data
      d.quality = 0;
    }
    try {
      double lat = Double.parseDouble(line.remove("LAT"));
      double lon = Double.parseDouble(line.remove("LON"));
      d.coordinates.setLocation(lon, lat);
    } catch (Exception e) {
      d.coordinates.setLocation(0.0, 0.0);
    }
    return d;
  }

  public static List<Provider> getProviderList() {
    return providerList;
  }

  @Override
  public double getX() {
    return coordinates.getX();
  }

  @Override
  public double getY() {
    return coordinates.getY();
  }

  public Point2D.Double getLonLat() {
    return coordinates;
  }

}
