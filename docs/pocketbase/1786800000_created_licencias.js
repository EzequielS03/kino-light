/// <reference path="../pb_data/types.d.ts" />
// Licencias: el derecho de uso que se da y se quita. NO es un codigo de invitacion -- se consume al
// registrarse pero sigue vivo, y el gateway lo mira en cada pedido. Por eso revocarla echa a la
// persona aunque ya este adentro.
//
// Reglas CERRADAS a proposito: nadie puede listar ni crear licencias desde la API. El unico camino
// es el CLI de `arkiv-api`, que entra como superusuario. Una licencia que se pueda crear desde
// internet es exactamente el agujero que esto viene a cerrar.
migrate((app) => {
  const licencias = new Collection({
    "name": "licencias",
    "type": "base",
    "system": false,
    "listRule": null,
    "viewRule": null,
    "createRule": null,
    "updateRule": null,
    "deleteRule": null,
    "indexes": [
      "CREATE UNIQUE INDEX `idx_licencias_codigo` ON `licencias` (`codigo`)"
    ],
    "fields": [
      { "name": "codigo", "type": "text", "required": true, "max": 64 },
      { "name": "estado", "type": "select", "required": true, "maxSelect": 1,
        "values": ["activa", "revocada"] },
      { "name": "maxCelulares", "type": "number", "required": true, "onlyInt": true },
      { "name": "maxTvs", "type": "number", "required": true, "onlyInt": true },
      { "name": "usadaPor", "type": "text", "max": 64 },
      { "name": "notas", "type": "text", "max": 200 }
    ]
  })
  app.save(licencias)
}, (app) => {
  app.delete(app.findCollectionByNameOrId("licencias"))
})
