<script setup>
import { computed, ref } from 'vue';

const defaultSql = `SHOW NODES;`;

const sql = ref(defaultSql);
const running = ref(false);
const results = ref([]);
const output = ref([]);
const history = ref([]);

const apiUrl = import.meta.env.VITE_MINISQL_RPC || '/rpc';

const gridResult = computed(() => {
  return [...results.value].reverse().find((item) => item.ok && item.columns.length) || null;
});
const columns = computed(() => gridResult.value?.columns || []);
const rows = computed(() => gridResult.value?.rows || []);

function splitStatements(text) {
  return text
    .split(';')
    .map((part) => part.trim())
    .filter(Boolean)
    .map((part) => `${part};`);
}

async function executeSql() {
  const statements = splitStatements(sql.value);
  if (!statements.length || running.value) {
    return;
  }

  running.value = true;
  results.value = [];
  output.value = [];

  try {
    for (const statement of statements) {
      const startedAt = performance.now();
      const id = crypto.randomUUID ? crypto.randomUUID() : String(Date.now());
      const body = {
        jsonrpc: '2.0',
        method: 'executeSql',
        params: { sql: statement },
        id
      };

      try {
        const response = await fetch(apiUrl, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body)
        });
        const payload = await response.json();
        if (payload.error) {
          throw new Error(payload.error.message || JSON.stringify(payload.error));
        }

        const result = payload.result || { columns: [], rows: [], message: '' };
        const rows = result.rows || [];
        const item = {
          sql: statement,
          ok: true,
          columns: result.columns || [],
          rows,
          message: result.message || `${rows.length} row${rows.length === 1 ? '' : 's'} returned`,
          durationMs: Math.round(performance.now() - startedAt),
          time: new Date().toLocaleTimeString()
        };
        results.value.push(item);
        output.value.push(item);
      } catch (err) {
        const item = {
          sql: statement,
          ok: false,
          columns: [],
          rows: [],
          message: err?.message || String(err),
          durationMs: Math.round(performance.now() - startedAt),
          time: new Date().toLocaleTimeString()
        };
        results.value.push(item);
        output.value.push(item);
        break;
      }
    }

    history.value.unshift({
      sql: statements.join('\n'),
      ok: output.value.every((item) => item.ok),
      time: new Date().toLocaleTimeString(),
      results: cloneItems(results.value),
      output: cloneItems(output.value)
    });
  } finally {
    running.value = false;
  }
}

function statusMessage(item) {
  if (!item.ok) {
    return item.message;
  }
  if (item.columns.length) {
    return `${item.rows.length} row${item.rows.length === 1 ? '' : 's'} returned`;
  }
  return item.message || 'success';
}

function clearSql() {
  sql.value = '';
  results.value = [];
  output.value = [];
}

function cloneItems(items) {
  return JSON.parse(JSON.stringify(items));
}

function useHistory(item) {
  sql.value = item.sql;
  results.value = cloneItems(item.results || []);
  output.value = cloneItems(item.output || []);
}
</script>

<template>
  <main class="query-shell">
    <header class="topbar">
      <div>
        <h1>MiniSQL Query</h1>
        <p>Master RPC: {{ apiUrl }}</p>
      </div>
      <button class="primary-action" :disabled="running || !sql.trim()" @click="executeSql">
        {{ running ? 'Running' : 'Run' }}
      </button>
    </header>

    <section class="workspace">
      <aside class="history-panel">
        <div class="panel-title">History</div>
        <button
          v-for="item in history"
          :key="item.time + item.sql"
          class="history-item"
          :class="{ failed: !item.ok }"
          @click="useHistory(item)"
        >
          <span>{{ item.time }}</span>
          <strong>{{ item.ok ? 'OK' : 'ERR' }}</strong>
          <code>{{ item.sql }}</code>
        </button>
        <div v-if="history.length === 0" class="empty-history">No queries yet</div>
      </aside>

      <section class="query-panel">
        <div class="editor-toolbar">
          <span>SQL</span>
          <button class="ghost-action" @click="clearSql">Clear</button>
        </div>
        <textarea
          v-model="sql"
          class="sql-editor"
          spellcheck="false"
          @keydown.ctrl.enter.prevent="executeSql"
        />

        <section class="grid-area">
          <div class="result-header">
            <span>Result Grid</span>
            <span v-if="gridResult">{{ rows.length }} row{{ rows.length === 1 ? '' : 's' }}</span>
          </div>

          <div v-if="columns.length" class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th class="row-number"></th>
                  <th v-for="column in columns" :key="column">{{ column }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, rowIndex) in rows" :key="rowIndex">
                  <td class="row-number">{{ rowIndex + 1 }}</td>
                  <td v-for="column in columns" :key="column">{{ row[column] }}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <div v-else class="empty-result">No result grid for the executed statements</div>
        </section>

        <section class="output-area">
          <div class="result-header">
            <span>Action Output</span>
            <span v-if="output.length">{{ output.length }} statement{{ output.length === 1 ? '' : 's' }}</span>
          </div>
          <div class="output-table-wrap">
            <table class="output-table">
              <thead>
                <tr>
                  <th>Status</th>
                  <th>Time</th>
                  <th>Action</th>
                  <th>Message</th>
                  <th>Duration / Fetch</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(item, index) in output" :key="index" :class="{ failed: !item.ok }">
                  <td class="status-cell">{{ item.ok ? 'OK' : 'ERR' }}</td>
                  <td>{{ item.time }}</td>
                  <td><code>{{ item.sql }}</code></td>
                  <td>{{ statusMessage(item) }}</td>
                  <td>{{ (item.durationMs / 1000).toFixed(3) }} sec</td>
                </tr>
                <tr v-if="output.length === 0">
                  <td colspan="5" class="empty-output">Run SQL to view action output</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </section>
    </section>
  </main>
</template>
