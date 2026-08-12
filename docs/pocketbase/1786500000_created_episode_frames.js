/// <reference path="../pb_data/types.d.ts" />
// Miniaturas de frame: el JPEG que se captura durante la reproduccion, para que se vea en que
// punto va cada capitulo. Fase 2 = sincronizarlo entre dispositivos.
//
// Mismo patron que `progress` y `live_favorites`: LWW por updatedAt, filtrado por accountId en las
// cinco reglas. La clave natural es (accountId, episodeId) y va con indice UNICO: el cliente hace
// upsert buscando por esa pareja, y sin unicidad un push concurrente crearia dos records para el
// mismo capitulo.
//
// `img` va PROTEGIDO a proposito: son escenas de lo que mira el usuario. Con protected=true la URL
// del archivo no sirve sola, hace falta pedir un file-token a /api/files/token. Es un paso mas en
// el cliente, y es el precio de no apoyarse en que la URL sea dificil de adivinar.
migrate((app) => {
  const frames = new Collection({
    "createRule": "@request.auth.id != \"\" && accountId = @request.auth.accountId",
    "deleteRule": "@request.auth.id != \"\" && accountId = @request.auth.accountId",
    "listRule": "@request.auth.id != \"\" && accountId = @request.auth.accountId",
    "updateRule": "@request.auth.id != \"\" && accountId = @request.auth.accountId",
    "viewRule": "@request.auth.id != \"\" && accountId = @request.auth.accountId",
    "name": "episode_frames",
    "type": "base",
    "system": false,
    "indexes": [
      "CREATE UNIQUE INDEX `idx_episode_frames_acct_ep` ON `episode_frames` (`accountId`, `episodeId`)"
    ],
    "fields": [
      {
        "autogeneratePattern": "[a-z0-9]{15}",
        "hidden": false,
        "id": "text3208210256",
        "max": 15,
        "min": 15,
        "name": "id",
        "pattern": "^[a-z0-9]+$",
        "presentable": false,
        "primaryKey": true,
        "required": true,
        "system": true,
        "type": "text"
      },
      {
        "autogeneratePattern": "",
        "hidden": false,
        "id": "text_ef_acct",
        "max": 0,
        "min": 0,
        "name": "accountId",
        "pattern": "",
        "presentable": false,
        "primaryKey": false,
        "required": true,
        "system": false,
        "type": "text"
      },
      {
        "autogeneratePattern": "",
        "hidden": false,
        "id": "text_ef_ep",
        "max": 0,
        "min": 0,
        "name": "episodeId",
        "pattern": "",
        "presentable": false,
        "primaryKey": false,
        "required": true,
        "system": false,
        "type": "text"
      },
      {
        "hidden": false,
        "id": "num_ef_pos",
        "max": null,
        "min": null,
        "name": "positionMs",
        "onlyInt": false,
        "presentable": false,
        "required": false,
        "system": false,
        "type": "number"
      },
      {
        "hidden": false,
        "id": "num_ef_upd",
        "max": null,
        "min": null,
        "name": "updatedAt",
        "onlyInt": false,
        "presentable": false,
        "required": false,
        "system": false,
        "type": "number"
      },
      {
        "hidden": false,
        "id": "num_ef_del",
        "max": null,
        "min": null,
        "name": "deleted",
        "onlyInt": false,
        "presentable": false,
        "required": false,
        "system": false,
        "type": "number"
      },
      {
        "hidden": false,
        "id": "file_ef_img",
        "maxSelect": 1,
        "maxSize": 262144,
        "mimeTypes": [
          "image/jpeg"
        ],
        "name": "img",
        "presentable": false,
        "protected": true,
        "required": false,
        "system": false,
        "thumbs": [],
        "type": "file"
      }
    ]
  });

  return app.save(frames);
}, (app) => {
  const frames = app.findCollectionByNameOrId("episode_frames");
  return app.delete(frames);
})
