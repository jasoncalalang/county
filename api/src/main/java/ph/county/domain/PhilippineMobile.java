package ph.county.domain;

/** Canonical mobile number stored for tenant-local uniqueness. */
public final class PhilippineMobile {
  private PhilippineMobile() {}

  public static String normalize(String value) {
    if (value == null || !value.matches("[+0-9 -]+")) {
      throw new IllegalArgumentException("Enter a Philippine mobile number");
    }
    String digits = value.replace(" ", "").replace("-", "");
    if (digits.matches("09[0-9]{9}")) return "+63" + digits.substring(1);
    if (digits.matches("9[0-9]{9}")) return "+63" + digits;
    if (digits.matches("639[0-9]{9}")) return "+" + digits;
    if (digits.matches("\\+639[0-9]{9}")) return digits;
    throw new IllegalArgumentException("Enter a Philippine mobile number");
  }
}
