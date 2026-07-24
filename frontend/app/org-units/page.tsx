"use client";

import { useEffect, useMemo, useState, type FormEvent } from "react";
import Shell from "../components/Shell";
import { EmptyState, SkeletonRows } from "../components/ui";
import { api } from "../lib/api";
import type { OrgUnit, TeamMember } from "../lib/types";

const UNIT_TYPES = ["DEPARTMENT", "TEAM", "BRANCH", "REGION", "COST_CENTER"];

/** Depth-first order (parents before children) with a depth for indentation. Cycle-safe. */
function ordered(units: OrgUnit[]): { unit: OrgUnit; depth: number }[] {
  const byParent = new Map<string | null, OrgUnit[]>();
  for (const u of units) {
    const key = u.parentUnitId;
    (byParent.get(key) ?? byParent.set(key, []).get(key)!).push(u);
  }
  for (const list of byParent.values()) list.sort((a, b) => a.name.localeCompare(b.name));
  const known = new Set(units.map((u) => u.id));
  const out: { unit: OrgUnit; depth: number }[] = [];
  const seen = new Set<string>();
  function walk(parent: string | null, depth: number) {
    for (const u of byParent.get(parent) ?? []) {
      if (seen.has(u.id)) continue;
      seen.add(u.id);
      out.push({ unit: u, depth });
      walk(u.id, depth + 1);
    }
  }
  walk(null, 0);
  // Roots whose parent is outside the set (shouldn't happen within a tenant, but never drop rows).
  for (const u of units) {
    if (!seen.has(u.id) && (u.parentUnitId === null || !known.has(u.parentUnitId))) {
      seen.add(u.id);
      out.push({ unit: u, depth: 0 });
      walk(u.id, 1);
    }
  }
  return out;
}

export default function OrgUnitsPage() {
  const [units, setUnits] = useState<OrgUnit[] | null>(null);
  const [members, setMembers] = useState<TeamMember[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [name, setName] = useState("");
  const [type, setType] = useState(UNIT_TYPES[0]);
  const [parentId, setParentId] = useState("");

  const [assignUser, setAssignUser] = useState("");
  const [assignUnit, setAssignUnit] = useState("");

  function load() {
    api.listOrgUnits().then(setUnits).catch((e) => setError((e as Error).message));
    api.listUsers().then(setMembers).catch(() => {});
  }
  useEffect(load, []);

  const rows = useMemo(() => (units ? ordered(units) : []), [units]);

  async function create(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setNote(null);
    setBusy(true);
    try {
      const u = await api.createOrgUnit(name.trim(), type, parentId || null);
      setNote(`Created unit “${u.name}”.`);
      setName("");
      setParentId("");
      load();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function assign(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setNote(null);
    setBusy(true);
    try {
      await api.assignOrgUnitMember(assignUnit, assignUser);
      const who = members.find((m) => m.id === assignUser)?.email ?? "user";
      const where = units?.find((u) => u.id === assignUnit)?.name ?? "unit";
      setNote(`Assigned ${who} to ${where}. They now see only that unit's subtree.`);
      setAssignUser("");
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Shell active="/org-units">
      <header className="topbar">
        <div>
          <p className="eyebrow">Organisation</p>
          <h1>Org units</h1>
          <p className="sub">
            Build the org hierarchy and scope people to it. A member assigned to a unit sees only accounts in that
            unit and everything beneath it; an unassigned member stays tenant-wide.
          </p>
        </div>
      </header>
      {error && <p className="error">{error}</p>}
      {note && <p className="ok">{note}</p>}

      <section className="panel">
        <div className="panelHeader">
          <div>
            <h2>Create a unit</h2>
            <p className="sub">Attach it under a parent to nest it, or leave the parent empty for a top-level unit.</p>
          </div>
        </div>
        <div className="panelBody">
          <form className="row" onSubmit={create}>
            <div style={{ flex: 1, minWidth: 200 }}>
              <label htmlFor="ou-name" style={{ marginTop: 0 }}>Name</label>
              <input id="ou-name" value={name} onChange={(e) => setName(e.target.value)} required placeholder="EU Operations" />
            </div>
            <div>
              <label htmlFor="ou-type" style={{ marginTop: 0 }}>Type</label>
              <select id="ou-type" value={type} onChange={(e) => setType(e.target.value)} style={{ minWidth: 160 }}>
                {UNIT_TYPES.map((t) => <option key={t} value={t}>{t.replace(/_/g, " ").toLowerCase()}</option>)}
              </select>
            </div>
            <div>
              <label htmlFor="ou-parent" style={{ marginTop: 0 }}>Parent</label>
              <select id="ou-parent" value={parentId} onChange={(e) => setParentId(e.target.value)} style={{ minWidth: 200 }}>
                <option value="">— none (top level) —</option>
                {rows.map(({ unit, depth }) => (
                  <option key={unit.id} value={unit.id}>{`${"— ".repeat(depth)}${unit.name}`}</option>
                ))}
              </select>
            </div>
            <button type="submit" disabled={busy || !name.trim()}>{busy ? "Creating…" : "Create unit"}</button>
          </form>
        </div>
      </section>

      <section className="panel" style={{ marginTop: 18 }}>
        <div className="panelHeader">
          <div>
            <h2>Assign a member</h2>
            <p className="sub">Grants the member the unit&apos;s subtree scope, using their current role.</p>
          </div>
        </div>
        <div className="panelBody">
          <form className="row" onSubmit={assign}>
            <div style={{ flex: 1, minWidth: 220 }}>
              <label htmlFor="as-user" style={{ marginTop: 0 }}>Member</label>
              <select id="as-user" value={assignUser} onChange={(e) => setAssignUser(e.target.value)} required style={{ width: "100%" }}>
                <option value="" disabled>Select a member…</option>
                {members.map((m) => <option key={m.id} value={m.id}>{m.email} · {m.role.replace(/_/g, " ").toLowerCase()}</option>)}
              </select>
            </div>
            <div style={{ flex: 1, minWidth: 200 }}>
              <label htmlFor="as-unit" style={{ marginTop: 0 }}>Org unit</label>
              <select id="as-unit" value={assignUnit} onChange={(e) => setAssignUnit(e.target.value)} required style={{ width: "100%" }}>
                <option value="" disabled>Select a unit…</option>
                {rows.map(({ unit, depth }) => (
                  <option key={unit.id} value={unit.id}>{`${"— ".repeat(depth)}${unit.name}`}</option>
                ))}
              </select>
            </div>
            <button type="submit" disabled={busy || !assignUser || !assignUnit}>{busy ? "Assigning…" : "Assign"}</button>
          </form>
        </div>
      </section>

      <section className="panel" style={{ marginTop: 18 }}>
        <table>
          <thead>
            <tr><th>Unit</th><th>Type</th></tr>
          </thead>
          <tbody>
            {units === null && <SkeletonRows cols={2} />}
            {rows.map(({ unit, depth }) => (
              <tr key={unit.id}>
                <td style={{ paddingLeft: 12 + depth * 22 }}>
                  {depth > 0 && <span className="muted" aria-hidden>↳ </span>}
                  {unit.name}
                </td>
                <td className="muted">{unit.type.replace(/_/g, " ").toLowerCase()}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {units !== null && units.length === 0 && (
          <EmptyState title="No org units yet" hint="Create your first unit above — until then, every member is tenant-wide." />
        )}
      </section>
    </Shell>
  );
}
