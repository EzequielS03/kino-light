/// <reference path="../pb_data/types.d.ts" />
migrate((app) => {
  // commands: agregar `stop` al select `type`.
  // El botón de parar de la barra del celu manda este comando al Fire TV. Sin este valor el server
  // RECHAZA el record y el comando falla en silencio (CloudTransport.send se traga la excepción),
  // que es exactamente cómo subprefs y webquality estuvieron rotos durante meses.
  const commands = app.findCollectionByNameOrId("pbc_3664792373")
  commands.fields.addAt(4, new Field({
    "help": "",
    "hidden": false,
    "id": "select2363381545",
    "maxSelect": 1,
    "name": "type",
    "presentable": false,
    "required": true,
    "system": false,
    "type": "select",
    "values": [
      "play",
      "key",
      "subprefs",
      "webquality",
      "pause",
      "resume",
      "seek",
      "next",
      "prev",
      "stop"
    ]
  }))

  return app.save(commands)
}, (app) => {
  const commands = app.findCollectionByNameOrId("pbc_3664792373")
  commands.fields.addAt(4, new Field({
    "help": "",
    "hidden": false,
    "id": "select2363381545",
    "maxSelect": 1,
    "name": "type",
    "presentable": false,
    "required": true,
    "system": false,
    "type": "select",
    "values": [
      "play",
      "key",
      "subprefs",
      "webquality",
      "pause",
      "resume",
      "seek",
      "next",
      "prev"
    ]
  }))

  return app.save(commands)
})
