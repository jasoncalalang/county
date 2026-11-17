package ph.com.ridgewell.county.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
class OpenApiContractTest {
  @Test
  void nullableResponseFieldsAreExplicitInTheContract() {
    var contract =
        given()
            .basePath("")
            .accept("application/json")
            .get("/q/openapi")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    for (String field :
        new String[] {
          "UserView.displayName",
          "UserView.email",
          "MeView.tenant",
          "ResellerView.lastOrderAt",
          "OrderView.confirmedAt",
          "OrderView.deliveredAt",
          "OrderView.cancelledAt",
          "OrderView.paidAt"
        }) {
      String[] pieces = field.split("\\.");
      Map<String, Object> schema =
          contract.getMap("components.schemas." + pieces[0] + ".properties." + pieces[1]);
      assertTrue(allowsNull(schema), "Missing nullability: " + field + " " + schema);
    }
  }

  @SuppressWarnings("unchecked")
  private boolean allowsNull(Map<String, Object> schema) {
    if (schema == null) return false;
    Object type = schema.get("type");
    if (Boolean.TRUE.equals(schema.get("nullable"))
        || "null".equals(type)
        || (type instanceof java.util.List<?> types && types.contains("null"))) return true;
    for (String composition : new String[] {"anyOf", "oneOf"}) {
      if (schema.get(composition) instanceof java.util.List<?> alternatives)
        for (Object alternative : alternatives)
          if (alternative instanceof Map<?, ?> map && allowsNull((Map<String, Object>) map))
            return true;
    }
    return false;
  }

  @Test
  void successResponsesExposeTypedSchemasIncludingCreationAndReplay() {
    var contract =
        given()
            .basePath("")
            .accept("application/json")
            .get("/q/openapi")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    Map<String, Object> schemas = contract.getMap("components.schemas");
    for (String name :
        new String[] {
          "BusinessView",
          "ProductView",
          "ResellerView",
          "OrderView",
          "OrderLineView",
          "CatalogView",
          "ProductionRunView",
          "MeView"
        }) assertNotNull(schemas.get(name), "Missing typed schema: " + name);
    Map<String, Object> orderProperties =
        contract.getMap("components.schemas.OrderView.properties");
    assertFalse(orderProperties.containsKey("tenantId"));
    assertFalse(orderProperties.containsKey("idempotencyKey"));
    assertEquals(
        "#/components/schemas/OrderLineView",
        contract.getString("components.schemas.OrderView.properties.lines.items.'$ref'"));
    for (String path :
        new String[] {
          "/api/settings",
          "/api/products",
          "/api/resellers",
          "/api/orders",
          "/api/orders/{id}",
          "/api/order/{token}",
          "/api/production-run"
        }) {
      Map<String, Object> schema =
          contract.getMap(
              "paths.'" + path + "'.get.responses.'200'.content.'application/json'.schema");
      assertNotNull(schema, path);
      assertTrue(
          schema.containsKey("$ref") || schema.containsKey("items"), "Untyped response: " + path);
    }
    for (String status : new String[] {"200", "201"})
      assertEquals(
          "#/components/schemas/OrderView",
          contract.getString(
              "paths.'/api/order/{token}/orders'.post.responses.'"
                  + status
                  + "'.content.'application/json'.schema.'$ref'"));
    assertEquals(
        "#/components/schemas/ProductView",
        contract.getString(
            "paths.'/api/products'.post.responses.'201'.content.'application/json'.schema.'$ref'"));
  }
}
