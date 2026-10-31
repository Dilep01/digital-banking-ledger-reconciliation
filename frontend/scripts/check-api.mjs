import SwaggerParser from "@apidevtools/swagger-parser";
const document = await SwaggerParser.validate("../docs/openapi.yaml");
console.log(
  `Validated OpenAPI ${document.openapi}: ${Object.keys(document.paths).length} paths.`,
);
