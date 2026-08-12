/// <reference path="../pb_data/types.d.ts" />
// users: la PERSONA. Hasta ahora la identidad era el device (`devices`) y la persona no existia:
// `accountId` agrupaba devices pero nadie podia autenticarse "como esa persona".
//
// `licencia` guarda el CODIGO, no una relacion: el gateway resuelve la licencia por codigo en cada
// validacion, y una relation obligaria a expandirla en cada consulta sin darnos nada a cambio.
migrate((app) => {
  const users = new Collection({
    "name": "users",
    "type": "auth",
    "system": false,
    "listRule": "id = @request.auth.id",
    "viewRule": "id = @request.auth.id",
    "createRule": null,
    "updateRule": "id = @request.auth.id",
    "deleteRule": null,
    "passwordAuth": { "enabled": true, "identityFields": ["email"] },
    "indexes": [
      "CREATE INDEX `idx_users_accountId` ON `users` (`accountId`)"
    ],
    "fields": [
      { "name": "accountId", "type": "text", "required": true, "max": 64 },
      { "name": "licencia", "type": "text", "required": true, "max": 64 }
    ]
  })
  app.save(users)
}, (app) => {
  app.delete(app.findCollectionByNameOrId("users"))
})
