/// <reference path="../pb_data/types.d.ts" />
migrate((app) => {
  // devices: campo nowPlaying — el TV publica ahí el estado de reproducción y el celu lo lee
  // para pintar el miniplayer. Ver remote/NowPlayingCodec.kt en la app.
  const devices = app.findCollectionByNameOrId("pbc_3365342971")
  devices.fields.addAt(7, new Field({
    "help": "",
    "hidden": false,
    "id": "json3186745092",
    "maxSize": 4000,
    "name": "nowPlaying",
    "presentable": false,
    "required": false,
    "system": false,
    "type": "json"
  }))
  app.save(devices)

  // commands: ampliar el select `type`.
  // OJO: hasta ahora solo admitía play/key, así que los comandos subprefs y webquality que la app
  // ya enviaba venían siendo RECHAZADOS en silencio (CloudTransport.send se traga la excepción).
  // Se agregan esos dos más los cinco de transporte del miniplayer.
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
}, (app) => {
  const devices = app.findCollectionByNameOrId("pbc_3365342971")
  devices.fields.removeById("json3186745092")
  app.save(devices)

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
      "key"
    ]
  }))

  return app.save(commands)
})
