# MiniSQL Query Web

Vue 3 query UI for the MiniSQL master RPC.

## Run

```powershell
cd C:\Users\Lenovo\Desktop\big_infor_sys_build_tech\Distributed_Database\web-query
npm install
npm run dev
```

Open `http://127.0.0.1:5173`.

The dev server proxies `/rpc` to `http://127.0.0.1:8080`, so start the MiniSQL master first.

To use another master URL:

```powershell
$env:VITE_MINISQL_RPC="http://127.0.0.1:8080/rpc"
npm run dev
```
