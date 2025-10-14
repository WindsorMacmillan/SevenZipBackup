const { db } = require('../app.js');
const express = require('express');
const router = express.Router();

router.get('/', async function (req, res) {
  if (req.query.state === undefined || req.query.code === undefined || req.query.error != undefined) return res.sendFile(process.cwd() + '/views/fail.html');

  await db.collection('pins').doc(req.query.state).update({ auth_code: req.query.code });

  res.sendFile(process.cwd() + '/views/success.html');
});

module.exports = router;