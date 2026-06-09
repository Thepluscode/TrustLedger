const metrics = [
  ['Money moved today', '£184,220', '+12.4%'],
  ['Held transfers', '18', '£42,900 reserved'],
  ['Fraud cases', '7', '3 critical'],
  ['Reconciliation issues', '1', 'needs review']
];

const cases = [
  ['CASE-1042', '£4,500', '82', 'New device + new beneficiary + amount anomaly', 'Held'],
  ['CASE-1041', '£900', '58', 'Step-up MFA required', 'Waiting MFA'],
  ['CASE-1038', '£12,000', '97', 'Blocked recipient match', 'Rejected']
];

export default function Home() {
  return (
    <main className="shell">
      <aside className="sidebar">
        <div className="brand">TrustLedger</div>
        <nav>
          <a className="active">Operations</a>
          <a>Transfers</a>
          <a>Ledger Explorer</a>
          <a>Fraud Cases</a>
          <a>Reconciliation</a>
          <a>Audit Logs</a>
          <a>Settings</a>
        </nav>
      </aside>
      <section className="content">
        <header className="topbar">
          <div>
            <p className="eyebrow">Financial Operations Cockpit</p>
            <h1>Ledger and fraud control centre</h1>
          </div>
          <button>Run reconciliation</button>
        </header>

        <section className="grid metrics">
          {metrics.map(([label, value, hint]) => (
            <article className="card" key={label}>
              <span>{label}</span>
              <strong>{value}</strong>
              <small>{hint}</small>
            </article>
          ))}
        </section>

        <section className="panel">
          <div className="panelHeader">
            <div>
              <h2>High-risk queue</h2>
              <p>Explainable fraud decisions awaiting analyst action.</p>
            </div>
            <button className="secondary">Export evidence</button>
          </div>
          <table>
            <thead>
              <tr><th>Case</th><th>Amount</th><th>Risk</th><th>Reason</th><th>Status</th></tr>
            </thead>
            <tbody>
              {cases.map((row) => (
                <tr key={row[0]}>
                  {row.map((cell, idx) => <td key={idx}>{cell}</td>)}
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      </section>
    </main>
  );
}
