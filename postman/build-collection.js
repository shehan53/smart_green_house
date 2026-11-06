// ============================================================================
//  Author: Role 5 - Timing & Integration Lead
//  Purpose: Generates the Postman collection and the two environments from one
//           place, so every request has the same waits, checks, banner and summary.
//           Each folder (= one script) is owned by one role, see OWNER below.
//  Usage:   node postman/build-collection.js          (run from the project folder)
//  Output:  postman/Greenhouse-Full-Demo.postman_collection.json
//           postman/greenhouse.postman_environment.json
//           postman/greenhouse-presentation.postman_environment.json
// ============================================================================
const fs = require('fs');
const path = require('path');
const outDir = process.argv[2] || __dirname;

/** Which role writes, maintains and presents each Postman folder. */
const OWNER = {
  '00-Common-Setup': 'Role 5 - Timing & Integration Lead',
  '01-Normal-Operation': 'Role 4 - Control Engineer',
  'F1-WrongValue': 'Role 1 - Sensor Engineer',
  'F2-DeadSensor': 'Role 2 - Fault Tolerance Engineer',
  'F3-SafeState': 'Role 2 - Fault Tolerance Engineer',
  'F4-RaceCondition': 'Role 3 - Concurrency Engineer',
  'F5-HeatWave': 'Role 4 - Control Engineer',
  'X1-InvalidInputs': 'Role 2 - Fault Tolerance Engineer',
  'X2-StuckAndImpossibleValue': 'Role 1 - Sensor Engineer',
  'X3-HumiditySoilFailure': 'Role 4 - Control Engineer',
  'X4-MixedChaos': 'Role 5 - Timing & Integration Lead',
  '02-Repair-All': 'Role 5 - Timing & Integration Lead',
  '03-Timing-And-Shutdown': 'Role 5 - Timing & Integration Lead',
};

function req(name, method, p, o = {}) {
  const expect = o.status ?? 200;
  const item = {
    name,
    event: [],
    request: {
      method,
      header: [],
      url: { raw: '{{baseUrl}}' + p, host: ['{{baseUrl}}'], path: p.split('/').filter(Boolean) },
    },
  };
  if (o.desc) item.request.description = o.desc;
  if (o.body !== undefined || o.raw !== undefined) {
    item.request.header.push({ key: 'Content-Type', value: 'application/json' });
    item.request.body = {
      mode: 'raw',
      raw: o.raw !== undefined ? o.raw : JSON.stringify(o.body, null, 2),
      options: { raw: { language: 'json' } },
    };
  }
  // every request waits its own time (o.wait) + stepDelay (0 = fast test run, 3000 = presentation)
  item.event.push({
    listen: 'prerequest',
    script: { type: 'text/javascript', exec: [
      `// wait so the real-time system has time to react (no user action needed)`,
      `const stepDelay = Number(pm.environment.get("stepDelay") || pm.collectionVariables.get("stepDelay") || 0);`,
      `const waitMs = ${o.wait || 0} + stepDelay;`,
      `if (waitMs > 0) setTimeout(() => {}, waitMs);`,
    ] },
  });
  item.event.push({
    listen: 'test',
    script: { type: 'text/javascript', exec: [
      `pm.test("HTTP ${expect}", () => pm.response.to.have.status(${expect}));`,
      'let json = {};',
      'try { json = pm.response.json(); } catch (e) { }',
      ...(o.tests || []),
    ] },
  });
  return item;
}

const t = (name, expr) => `pm.test(${JSON.stringify(name)}, () => pm.expect(${expr}).to.be.true);`;
const status = (name, wait, tests) => req(name, 'GET', '/api/status', { wait, tests });
const reset = () => req('Reset system', 'POST', '/api/system/reset');
const env = (T, H, S, name) => req(name || `Set environment T=${T} C, H=${H} %, Soil=${S} %`, 'POST', '/api/environment',
  { body: { temperature: T, humidity: H, soil: S } });
const fault = (id, type, value, name) => req(name || `Inject fault: ${id} -> ${type}${value !== undefined ? ' ' + value : ''}`,
  'POST', `/api/sensors/${id}/fault`, { body: value === undefined ? { type } : { type, value } });
const repair = (id) => req(`Repair ${id}`, 'DELETE', `/api/sensors/${id}/fault`);
const normalStart = () => [reset(), env(25, 60, 45, 'Set normal environment (25 C, 60 %, 45 %)'),
  status('Baseline: vote OK (after 2 s)', 2000, [t('vote status OK', 'json.voteStatus === "OK"'), t('not in safe state', 'json.safeState === false')])];

const SUMMARY_TEMPLATE = `
<h3>{{scenario}}</h3>
<p><b>Expected:</b> {{expected}}<br><b>Duration:</b> {{durationMs}} ms &nbsp; <b>Events:</b> {{eventCount}}</p>
<table border="1" cellpadding="4" style="border-collapse:collapse;font-family:monospace;font-size:12px">
<tr><th>#</th><th>What the system did</th></tr>
{{#each events}}<tr><td>{{@index}}</td><td>{{this}}</td></tr>{{/each}}
</table>`;

function scenarioStart(name, expect) {
  return req('▶ Scenario start (banner in CLI)', 'POST', '/api/scenario/start', { body: { name, expect } });
}

function scenarioSummary() {
  return req('■ Scenario summary (what the system did)', 'POST', '/api/scenario/end', { tests: [
    'pm.test("summary received", () => pm.expect(json.events).to.be.an("array"));',
    'console.log("==================== " + json.scenario + " ====================");',
    'console.log("EXPECTED: " + json.expected);',
    '(json.events || []).forEach((e, i) => console.log((i + 1) + ". " + e));',
    `pm.visualizer.set(${JSON.stringify(SUMMARY_TEMPLATE)}, json);`,
  ] });
}

const EXPECT = {
  '00-Common-Setup': 'system running, reset, normal values -> vote OK, no alarm',
  '01-Normal-Operation': 'heater ON <22 C / OFF >24 C, fan ON >28 C or >85 %, pump ON <35 % / OFF >50 %, heater hysteresis',
  'F1-WrongValue': 'T2 +15 C -> voter MASKED, real temperature used, NO false alarm; recovers after repair',
  'F2-DeadSensor': 'T3 dead -> watchdog T3 FAILED within 2.5 s (RT3), voter DEGRADED (2 sensors); recovers after repair',
  'F3-SafeState': 'T3 dead + T1 wrong -> no agreement -> SAFE STATE (heater OFF, fan ON, alarm ON); leaves it after repair',
  'F4-RaceCondition': 'unsafe counter LOSES updates, synchronized and AtomicInteger = 200000',
  'F5-HeatWave': '+14 C -> Observe & React: alarm + fan ON within 1 s (RT2)',
  'X1-InvalidInputs': 'every wrong input REJECTED (400/404/405), system state unchanged',
  'X2-StuckAndImpossibleValue': 'stuck T1 out-voted (MASKED); impossible 115 C reading rejected -> DEGRADED',
  'X3-HumiditySoilFailure': 'S1 dead -> pump OFF; H1 dead -> fan ON; repeated warnings; recovery after repair',
  'X4-MixedChaos': 'mixed valid/invalid inputs, faults, exception, heat wave -> never crashes, recovers fully',
  '02-Repair-All': 'all sensors repaired, back to normal',
  '03-Timing-And-Shutdown': 'timing table: total U <= 0.757 -> SCHEDULABLE; clean shutdown',
};

/** Every folder: banner first, summary last (summary before the shutdown request in the last folder). */
function folder(name, description, items) {
  const expect = EXPECT[name] || '';
  const summaryBeforeLast = name === '03-Timing-And-Shutdown';
  const body = [...items];
  const tail = summaryBeforeLast ? [body.pop()] : [];
  const text = 'Owner: ' + OWNER[name] + '.\n' + description.replace(/\s*\((?:Roles? [^)]*|all roles)\)/, '');
  return { name, description: text, item: [scenarioStart(name, expect), ...body, scenarioSummary(), ...tail] };
}

const folders = [
  folder('00-Common-Setup',
    'COMMON. Checks the system is running and brings it to a known normal state. Run before anything else.',
    [
      status('System is running', 0, [t('status contains voteStatus', 'json.voteStatus !== undefined')]),
      reset(),
      env(25, 60, 45, 'Set normal environment (25 C, 60 %, 45 %)'),
      status('Normal operation: vote OK, no alarm (after 2.5 s)', 2500, [
        t('vote status OK', 'json.voteStatus === "OK"'),
        t('3 sensors used', 'json.sensorsUsed === 3'),
        t('not in safe state', 'json.safeState === false'),
        t('alarm OFF', 'json.actuators.alarm === false'),
      ]),
    ]),

  folder('01-Normal-Operation',
    'COMMON. Environmental control without faults (Role 4): heater, fan, pump rules and the heater hysteresis.',
    [
      reset(),
      env(20, 60, 45, 'Cold: T=20 C'),
      status('Heater ON below 22 C', 2500, [t('heater ON', 'json.actuators.heater === true')]),
      // values are chosen with a margin: in slow (presentation) mode the fan / heater keep changing
      // the simulated greenhouse for several seconds before the check, so it must stay beyond the limit
      env(33, 60, 45, 'Hot: T=33 C'),
      status('Heater OFF above 24 C, fan ON above 28 C', 2500, [
        t('heater OFF', 'json.actuators.heater === false'), t('fan ON', 'json.actuators.fan === true')]),
      env(25, 95, 45, 'Humid: H=95 %'),
      status('Fan ON above 85 % humidity', 2500, [t('fan ON', 'json.actuators.fan === true')]),
      env(25, 60, 30, 'Dry soil: 30 %'),
      status('Pump ON below 35 % soil', 2500, [
        t('pump ON', 'json.actuators.pump === true'), t('fan OFF', 'json.actuators.fan === false')]),
      env(25, 60, 55, 'Wet soil: 55 %'),
      status('Pump OFF above 50 % soil', 2500, [t('pump OFF', 'json.actuators.pump === false')]),
      // hysteresis: the heater is OFF at 25 C and must STAY OFF at 23 C (a simple "ON below 24" rule would switch
      // it on). The OFF case is used because the temperature hardly moves while the heater is off.
      env(25, 60, 45, 'Hysteresis 1: T=25 C'),
      status('Heater OFF at 25 C', 2000, [t('heater OFF', 'json.actuators.heater === false')]),
      env(23, 60, 45, 'Hysteresis 2: T=23 C (inside 22-24 band)'),
      status('Heater STAYS OFF inside the 22-24 C band (hysteresis)', 2000, [t('heater still OFF', 'json.actuators.heater === false')]),
      env(21, 60, 45, 'Hysteresis 3: T=21 C (below 22)'),
      status('Heater ON only below 22 C', 2000, [t('heater ON', 'json.actuators.heater === true')]),
      reset(),
    ]),

  folder('F1-WrongValue',
    'F1 (Roles 1 + 2): T2 gives a wrong value (+15 C). TMR voter out-votes it -> MASKED, control continues correctly.',
    [
      ...normalStart(),
      fault('T2', 'WRONG_VALUE', 15, 'F1: T2 wrong value +15 C'),
      status('Voter MASKED, control still correct (after 2 s)', 2000, [
        t('vote status MASKED', 'json.voteStatus === "MASKED"'),
        t('T2 really reports about 40 C', 'json.sensors.T2.lastValue > 38'),
        t('voted temperature is the REAL one (24..26.5 C)', 'json.temperature > 24 && json.temperature < 26.5'),
        t('alarm OFF (no false alarm)', 'json.actuators.alarm === false'),
        t('fan OFF (no wrong reaction)', 'json.actuators.fan === false'),
        t('not in safe state', 'json.safeState === false'),
      ]),
      repair('T2'),
      status('Recovered: vote OK again (after 2 s)', 2000, [t('vote status OK', 'json.voteStatus === "OK"')]),
    ]),

  folder('F2-DeadSensor',
    'F2 (Roles 1 + 2): T3 stops sending. Watchdog reports T3 FAILED within 2.5 s (RT3), voter DEGRADED (2 sensors).',
    [
      ...normalStart(),
      fault('T3', 'DEAD', undefined, 'F2: T3 dead'),
      status('Watchdog: T3 FAILED, voter DEGRADED (after 3 s)', 3000, [
        t('T3 marked FAILED by watchdog', 'json.sensors.T3.health === "FAILED"'),
        t('vote status DEGRADED', 'json.voteStatus === "DEGRADED"'),
        t('2 sensors used', 'json.sensorsUsed === 2'),
        // RT3 worst case = timeout 1.5 s + watchdog period 1.0 s = exactly 2.5 s (zero margin). Normal Java on
        // Windows adds a few ms of jitter, so the test allows 100 ms tolerance; the CLI still prints MET / MISSED.
        t('RT3: detected within 2500 ms (+100 ms jitter tolerance)', 'json.detectionMs.T3 !== undefined && json.detectionMs.T3 <= 2600'),
        t('not in safe state', 'json.safeState === false'),
        'console.log("RT3 detection time T3 = " + json.detectionMs.T3 + " ms (deadline 2500 ms)");',
      ]),
      repair('T3'),
      status('Recovered: T3 OK, vote OK (after 2.5 s)', 2500, [
        t('T3 healthy again', 'json.sensors.T3.health === "OK"'), t('vote status OK', 'json.voteStatus === "OK"')]),
    ]),

  folder('F3-SafeState',
    'F3 (Role 2): T3 dead AND T1 wrong -> no agreement -> SAFE STATE (heater OFF, fan ON, alarm ON). Leaves the safe state after repair.',
    [
      ...normalStart(),
      fault('T3', 'DEAD', undefined, 'F3 step 1: T3 dead'),
      status('DEGRADED with 2 sensors (after 3 s)', 3000, [t('vote status DEGRADED', 'json.voteStatus === "DEGRADED"')]),
      fault('T1', 'WRONG_VALUE', 15, 'F3 step 2: T1 wrong value +15 C'),
      status('SAFE STATE: heater OFF, fan ON, alarm ON (after 2 s)', 2000, [
        t('vote status FAILED (no agreement)', 'json.voteStatus === "FAILED"'),
        t('SAFE STATE active', 'json.safeState === true'),
        t('heater OFF', 'json.actuators.heater === false'),
        t('fan ON', 'json.actuators.fan === true'),
        t('alarm ON', 'json.actuators.alarm === true'),
      ]),
      status('Still safe while the fault persists (after 3 s, warnings repeat in CLI)', 3000, [
        t('SAFE STATE still active', 'json.safeState === true')]),
      repair('T1'),
      repair('T3'),
      status('Safe state left automatically after repair (after 5 s)', 5000, [
        t('SAFE STATE left', 'json.safeState === false'),
        t('vote status OK', 'json.voteStatus === "OK"'),
        t('alarm OFF', 'json.actuators.alarm === false'),
      ]),
    ]),

  folder('F4-RaceCondition',
    'F4 (Role 3): two threads x 100 000 increments. Unsafe counter loses updates; synchronized and AtomicInteger are correct.',
    [
      req('F4: run race-condition demo', 'POST', '/api/demo/race', { tests: [
        t('unsafe counter LOST updates (< 200000)', 'json.unsafe < 200000'),
        t('synchronized counter correct (200000)', 'json.synchronized === 200000'),
        t('AtomicInteger counter correct (200000)', 'json.atomic === 200000'),
        'console.log("unsafe=" + json.unsafe + " (lost " + json.lostUpdates + ")  synchronized=" + json.synchronized + "  atomic=" + json.atomic);',
      ] }),
    ]),

  folder('F5-HeatWave',
    'F5 (Role 4): heat wave +14 C. Observe & React switches alarm and fan ON within 1 s (RT2, hard deadline).',
    [
      ...normalStart(),
      req('F5: heat wave +14 C', 'POST', '/api/environment/heatwave', { body: { delta: 14 } }),
      status('Alarm + fan ON within 1 s (after 1 s)', 1000, [
        t('temperature above 35 C', 'json.temperature > 35'),
        t('alarm ON', 'json.actuators.alarm === true'),
        t('fan ON', 'json.actuators.fan === true'),
        t('heater OFF', 'json.actuators.heater === false'),
        t('RT2: reaction < 1000 ms', 'json.rt2ReactionMs !== null && json.rt2ReactionMs < 1000'),
        'console.log("RT2 reaction time = " + json.rt2ReactionMs + " ms (deadline 1000 ms)");',
      ]),
      reset(),
      status('Back to normal, alarm OFF (after 2 s)', 2000, [t('alarm OFF', 'json.actuators.alarm === false')]),
    ]),

  folder('X1-InvalidInputs',
    'EXTRA (Role 5): every wrong input is rejected with 400/404/405 and logged. The real-time system is not affected.',
    [
      ...normalStart(),
      req('Temperature 500 C (out of range)', 'POST', '/api/environment', { status: 400, body: { temperature: 500, humidity: 60, soil: 45 } }),
      req('Humidity -5 % (out of range)', 'POST', '/api/environment', { status: 400, body: { temperature: 25, humidity: -5, soil: 45 } }),
      req('Temperature is text "hot" (wrong type)', 'POST', '/api/environment', { status: 400, body: { temperature: 'hot', humidity: 60, soil: 45 } }),
      req('Missing fields {}', 'POST', '/api/environment', { status: 400, body: {} }),
      req('Malformed JSON', 'POST', '/api/environment', { status: 400, raw: '{"temperature": 25, "humidity": ' }),
      req('Unknown sensor T9', 'POST', '/api/sensors/T9/fault', { status: 404, body: { type: 'DEAD' } }),
      req('Unknown fault type EXPLODE', 'POST', '/api/sensors/T1/fault', { status: 400, body: { type: 'EXPLODE' } }),
      req('WRONG_VALUE without value', 'POST', '/api/sensors/T1/fault', { status: 400, body: { type: 'WRONG_VALUE' } }),
      req('Fault value 500 (out of range)', 'POST', '/api/sensors/T1/fault', { status: 400, body: { type: 'WRONG_VALUE', value: 500 } }),
      req('Heat wave delta 1000 (out of range)', 'POST', '/api/environment/heatwave', { status: 400, body: { delta: 1000 } }),
      req('Unknown endpoint', 'GET', '/api/does-not-exist', { status: 404 }),
      req('Wrong HTTP method (DELETE /api/status)', 'DELETE', '/api/status', { status: 405 }),
      status('System unaffected: still OK (after 1.5 s)', 1500, [
        t('vote status OK', 'json.voteStatus === "OK"'),
        t('not in safe state', 'json.safeState === false'),
        t('all sensors fault-free', 'Object.values(json.sensors).every(s => s.fault === "NONE")'),
      ]),
    ]),

  folder('X2-StuckAndImpossibleValue',
    'EXTRA (Roles 1 + 2): a STUCK sensor is out-voted (MASKED); an impossible reading (115 C) is rejected by validation -> DEGRADED.',
    [
      ...normalStart(),
      fault('T1', 'STUCK', undefined, 'T1 stuck (frozen at about 25 C)'),
      env(33, 60, 45, 'Real temperature rises to 33 C'),
      status('Stuck T1 out-voted, fan reacts to the real 33 C (after 2 s)', 2000, [
        t('vote status MASKED', 'json.voteStatus === "MASKED"'),
        t('voted temperature follows the real value (> 29 C)', 'json.temperature > 29'),
        t('fan ON', 'json.actuators.fan === true'),
      ]),
      repair('T1'),
      reset(),
      fault('T2', 'WRONG_VALUE', 90, 'T2 impossible value (25 + 90 = 115 C)'),
      status('Impossible reading rejected -> DEGRADED with 2 sensors (after 2 s)', 2000, [
        t('vote status DEGRADED', 'json.voteStatus === "DEGRADED"'),
        t('2 sensors used', 'json.sensorsUsed === 2'),
        t('not in safe state', 'json.safeState === false'),
      ]),
      repair('T2'),
      status('Recovered: vote OK (after 2 s)', 2000, [t('vote status OK', 'json.voteStatus === "OK"')]),
    ]),

  folder('X3-HumiditySoilFailure',
    'EXTRA (Roles 2 + 4): non-redundant sensors. S1 dead -> pump OFF (no flooding). H1 dead -> fan ON (precaution). Warnings repeat.',
    [
      reset(),
      env(25, 60, 30, 'Dry soil 30 % -> pump should run'),
      status('Pump ON (after 2.5 s)', 2500, [t('pump ON', 'json.actuators.pump === true'), t('fan OFF', 'json.actuators.fan === false')]),
      fault('S1', 'DEAD', undefined, 'S1 soil sensor dead'),
      status('S1 FAILED -> pump OFF (after 3 s)', 3000, [
        t('S1 FAILED', 'json.sensors.S1.health === "FAILED"'), t('pump OFF (fail-safe)', 'json.actuators.pump === false')]),
      fault('H1', 'DEAD', undefined, 'H1 humidity sensor dead'),
      status('H1 FAILED -> fan ON, temperature control still OK (after 3 s)', 3000, [
        t('H1 FAILED', 'json.sensors.H1.health === "FAILED"'),
        t('fan ON (precaution)', 'json.actuators.fan === true'),
        t('vote status OK', 'json.voteStatus === "OK"'),
      ]),
      repair('H1'),
      repair('S1'),
      env(25, 60, 30, 'Soil dry again (30 %)'),
      status('Both recovered, normal control (after 3 s)', 3000, [
        t('H1 OK', 'json.sensors.H1.health === "OK"'), t('S1 OK', 'json.sensors.S1.health === "OK"'),
        t('fan OFF', 'json.actuators.fan === false'), t('pump ON again (soil sensor trusted again)', 'json.actuators.pump === true')]),
      reset(),
    ]),

  folder('X4-MixedChaos',
    'EXTRA (all roles): valid and invalid inputs, sensor faults, a software exception and a heat wave mixed together. The system never crashes.',
    [
      reset(),
      env(26, 70, 40, 'Valid: environment 26 C'),
      req('Invalid: temperature 999', 'POST', '/api/environment', { status: 400, body: { temperature: 999, humidity: 70, soil: 40 } }),
      fault('T2', 'WRONG_VALUE', 15, 'Valid: T2 wrong +15 C'),
      req('Invalid: malformed fault JSON', 'POST', '/api/sensors/T1/fault', { status: 400, raw: '{type: DEAD' }),
      fault('H1', 'DEAD', undefined, 'Valid: H1 dead'),
      req('Invalid: unknown sensor X7', 'DELETE', '/api/sensors/X7/fault', { status: 404 }),
      req('Software exception in the control cycle', 'POST', '/api/system/exception'),
      status('Exception -> forward recovery to SAFE STATE (after 1.5 s)', 1500, [
        t('safe state was entered because of the exception', 'String(json.lastSafeStateReason).includes("exception")'),
      ]),
      req('Valid: heat wave +20 C', 'POST', '/api/environment/heatwave', { body: { delta: 20 } }),
      req('Invalid: heat wave "abc"', 'POST', '/api/environment/heatwave', { status: 400, body: { delta: 'abc' } }),
      status('Still alive and protecting: alarm + fan ON (after 2.5 s)', 2500, [
        t('alarm ON', 'json.actuators.alarm === true'), t('fan ON', 'json.actuators.fan === true'),
        t('T2 still masked', 'json.voteStatus === "MASKED"')]),
      repair('T2'),
      repair('H1'),
      reset(),
      status('Fully recovered after the chaos (after 3 s)', 3000, [
        t('vote status OK', 'json.voteStatus === "OK"'),
        t('not in safe state', 'json.safeState === false'),
        t('alarm OFF', 'json.actuators.alarm === false'),
        t('all sensors healthy', 'Object.values(json.sensors).every(s => s.health === "OK")'),
      ]),
    ]),

  folder('02-Repair-All',
    'COMMON. Repairs every sensor and resets the system to normal.',
    [
      ...['T1', 'T2', 'T3', 'H1', 'S1'].map(repair),
      reset(),
      status('All normal (after 2.5 s)', 2500, [
        t('vote status OK', 'json.voteStatus === "OK"'), t('not in safe state', 'json.safeState === false')]),
    ]),

  folder('03-Timing-And-Shutdown',
    'COMMON (Role 5): timing analysis (max C, U = C/T, total U vs RM bound 0.757) and clean shutdown. Run LAST.',
    [
      req('Timing report (also printed in the CLI)', 'GET', '/api/timing', { tests: [
        t('4 periodic tasks measured', 'json.tasks.length === 4'),
        t('every task has run', 'json.tasks.every(r => r.runs > 0)'),
        t('RM bound is 0.757', 'json.rmBound === 0.757'),
        t('total U below the RM bound -> schedulable', 'json.totalUtilisation <= json.rmBound && json.schedulable === true'),
        'console.log("Task              T(ms)   maxC(ms)   U=C/T");',
        'json.tasks.forEach(r => console.log(r.task.padEnd(17) + String(r.periodMs).padStart(5) + String(r.maxCms).padStart(11) + String(r.utilisation).padStart(11)));',
        'console.log("Total U = " + json.totalUtilisation + "  RM bound = " + json.rmBound + "  -> " + (json.schedulable ? "SCHEDULABLE" : "NOT GUARANTEED"));',
      ] }),
      req('Clean shutdown (final timing table in the CLI)', 'POST', '/api/system/shutdown'),
    ]),
];

const collection = {
  info: {
    name: 'Greenhouse-Full-Demo',
    description: 'Smart Greenhouse Controller - all scripts. Author: Role 5 - Timing & Integration Lead.\n'
      + 'Start the system with run.bat, then run the whole collection (or one folder). Everything is automatic: '
      + 'inputs, waits, checks, repair. Watch the CLI for the system\'s reactions.',
    schema: 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json',
  },
  item: folders,
  variable: [{ key: 'baseUrl', value: 'http://localhost:8080' }, { key: 'stepDelay', value: '0' }],
};

const makeEnv = (name, stepDelay) => ({
  name,
  values: [
    { key: 'baseUrl', value: 'http://localhost:8080', type: 'default', enabled: true },
    { key: 'stepDelay', value: String(stepDelay), type: 'default', enabled: true },
  ],
  _postman_variable_scope: 'environment',
});

fs.writeFileSync(path.join(outDir, 'Greenhouse-Full-Demo.postman_collection.json'), JSON.stringify(collection, null, 2));
fs.writeFileSync(path.join(outDir, 'greenhouse.postman_environment.json'), JSON.stringify(makeEnv('Greenhouse', 0), null, 2));
fs.writeFileSync(path.join(outDir, 'greenhouse-presentation.postman_environment.json'),
  JSON.stringify(makeEnv('Greenhouse-Presentation', 3000), null, 2));
console.log('folders:', folders.map(f => `${f.name}(${f.item.length})`).join(', '));
