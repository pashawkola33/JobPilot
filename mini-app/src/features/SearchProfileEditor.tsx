import { useEffect, useRef, useState } from 'react';
import { JobPilotError } from '../data/types';
import { createSearchProfileClient, SearchProfileValidationError, type SearchProfile, type SearchProfileUpdate, type SearchProfileView } from '../data/searchProfile';

const listKeys = ['desiredTitles', 'roleCategories', 'industries', 'technicalSkills', 'nontechnicalSkills', 'targetRegions'] as const;
type ListKey = typeof listKeys[number];
type Draft = Omit<SearchProfile, ListKey | 'salary'> & Record<ListKey, string> & {
  salaryBasis: 'UNKNOWN' | 'GROSS' | 'NET'; salaryAmount: string; salaryCurrency: string; salaryPeriod: '' | NonNullable<SearchProfile['salary']>['period'];
};
const labels = {
  UNKNOWN: 'Unknown / not specified', YES: 'Yes', NO: 'No', NO_PROFESSIONAL_EXPERIENCE: 'No professional experience',
  ENTRY: 'Entry level', MID: 'Mid level', SENIOR: 'Senior', LEAD: 'Lead',
  AUTHORIZED: 'Authorized in the countries I specify below', REQUIRES_SPONSORSHIP: 'Requires sponsorship', NOT_AUTHORIZED: 'Not authorized',
  STUDENT: 'Student', NOT_STUDENT: 'Not currently a student',
};
function draftOf(profile: SearchProfile): Draft {
  const { salary, ...rest } = profile;
  return { ...rest, authorizationByCountry: profile.authorizationByCountry ?? [], desiredTitles: profile.desiredTitles.join(', '), roleCategories: profile.roleCategories.join(', '),
    industries: profile.industries.join(', '), technicalSkills: profile.technicalSkills.join(', '),
    nontechnicalSkills: profile.nontechnicalSkills.join(', '), targetRegions: profile.targetRegions.join(', '),
    salaryBasis: salary?.basis ?? 'UNKNOWN', salaryAmount: salary?.amount.toString() ?? '', salaryCurrency: salary?.currency ?? '', salaryPeriod: salary?.period ?? '' };
}
const split = (value: string) => [...new Set(value.split(/[,\n]/).map((item) => item.trim()).filter(Boolean))];
function profileOf(draft: Draft): SearchProfile {
  const { salaryAmount, salaryCurrency, salaryPeriod, salaryBasis, ...rest } = draft;
  return { ...rest, desiredTitles: split(draft.desiredTitles), roleCategories: split(draft.roleCategories),
    industries: split(draft.industries), technicalSkills: split(draft.technicalSkills),
    nontechnicalSkills: split(draft.nontechnicalSkills), targetRegions: split(draft.targetRegions),
    salary: salaryAmount || salaryCurrency || salaryPeriod ? {
      basis: salaryBasis, amount: Number(salaryAmount), currency: salaryCurrency.trim().toUpperCase(), period: salaryPeriod as NonNullable<SearchProfile['salary']>['period'],
    } : null };
}

export function SearchProfileEditor({ initial, client, onSaved, onCancel, onReload }: {
  initial: SearchProfileView; client: ReturnType<typeof createSearchProfileClient>;
  onSaved: (view: SearchProfileView) => void; onCancel?: () => void; onReload: () => void;
}) {
  const [draft, setDraft] = useState(() => draftOf(initial.profile));
  const [step, setStep] = useState(0);
  const [error, setError] = useState('');
  const [conflict, setConflict] = useState(false);
  const [saving, setSaving] = useState(false);
  const pending = useRef<SearchProfileUpdate | null>(null);
  const inFlight = useRef(false);
  const alive = useRef(true);
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => { heading.current?.focus(); }, [step]);
  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => {
    // A changed payload receives a fresh operation key, while an unchanged manual retry keeps its key.
    pending.current = null; setDraft((old) => ({ ...old, [key]: value })); setError('');
  };
  function validate(): string {
    const p = profileOf(draft);
    if (step === 0 && !p.desiredTitles.length && !p.roleCategories.length) return 'Enter at least one desired job title or role category.';
    if (step === 2 && (!p.targetRegions.length || !p.workFormats.length)) return 'Choose at least one target country or region and work format.';
    if (step === 3 && p.salary && (!draft.salaryAmount || !Number.isFinite(p.salary.amount) || p.salary.amount <= 0
        || !/^[A-Z]{3}$/.test(p.salary.currency) || !p.salary.period)) return 'Salary needs a positive amount, a three-letter currency and a period, or leave all salary fields empty.';
    if (step === 3 && (p.authorizationByCountry ?? []).some(value => !/^[A-Z]{2}$/.test(value.country))) return 'Use a two-letter ISO country code for each authorization, or remove the row.';
    if (step === 3 && p.languages.some((language) => !language.language.trim())) return 'Name each language or remove its empty row.';
    return '';
  }
  const input = (label: string, key: ListKey | 'currentCountry' | 'currentCity' | 'authorizationDetails' | 'education' | 'salaryAmount' | 'salaryCurrency', hint?: string) =>
    <label className="profile-field">{label}<input aria-label={label} value={draft[key]} maxLength={listKeys.includes(key as ListKey) ? 4800 : key === 'salaryCurrency' ? 3 : key === 'salaryAmount' ? 14 : key === 'education' || key === 'authorizationDetails' ? 1000 : 120}
      inputMode={key === 'salaryAmount' ? 'decimal' : 'text'} autoComplete="off" onChange={(event) => set(key, event.target.value)} />
      {hint && <small>{hint}</small>}</label>;
  const select = <K extends 'experienceLevel' | 'workAuthorization' | 'studentStatus' | 'relocation'>(label: string, key: K, values: Draft[K][]) =>
    <label className="profile-field">{label}<select aria-label={label} value={draft[key]} onChange={(event) => set(key, event.target.value as Draft[K])}>
      {values.map((value) => <option value={value} key={value}>{labels[value]}</option>)}</select></label>;
  async function save() {
    if (inFlight.current || conflict) return;
    inFlight.current = true; setSaving(true); setError('');
    const request = pending.current ?? { expectedRevision: initial.activeRevision, requestId: crypto.randomUUID(), profile: profileOf(draft) };
    pending.current = request;
    try {
      const view = await client.save(request);
      if (alive.current) onSaved(view);
    } catch (failure) {
      if (!alive.current) return;
      if (failure instanceof JobPilotError && failure.kind === 'conflict') {
        setConflict(true);
      } else if (failure instanceof SearchProfileValidationError) {
        setError('Check your entries: each list item must be at most 120 characters, lists at most 40 items, and salary must use a valid currency and period.');
      } else setError('Save not confirmed. Your draft is still here. Try again with the same selections to safely confirm it.');
    } finally { inFlight.current = false; if (alive.current) setSaving(false); }
  }
  const summary = profileOf(draft);
  const display = (value: unknown): string => {
    if (value === null || value === '' || Array.isArray(value) && value.length === 0) return 'Not specified';
    if (Array.isArray(value)) return value.map(display).join(', ');
    if (typeof value === 'object' && value) return Object.entries(value).map(([key, item]) => `${key}: ${display(item)}`).join(' · ');
    if (value === 'UNKNOWN') return 'Unknown';
    return String(value).replaceAll('_', ' ');
  };
  const reviewLabels: Record<keyof SearchProfile, string> = { desiredTitles: 'Desired job titles', roleCategories: 'Role categories', industries: 'Industries of interest',
    technicalSkills: 'Technical skills', nontechnicalSkills: 'Nontechnical skills', experienceLevel: 'Experience level', statedExperience: 'Self-stated experience',
    experienceYears: 'Years of experience', currentCountry: 'Current country', currentCity: 'Current city', targetRegions: 'Target countries or regions',
    workFormats: 'Work format', employmentTypes: 'Employment type', languages: 'Languages', workAuthorization: 'Work authorization',
    authorizationByCountry: 'Country-specific authorization confirmations', authorizationDetails: 'Authorization countries and details', salary: 'Salary expectations', education: 'Education', studentStatus: 'Student status',
    relocation: 'Willing to relocate', additionalPreferences: 'Additional preferences' };
  return <main className="app profile-screen">
    <header><p className="profile-eyebrow">Search profile · Step {step + 1} of 5</p>
      <h1 tabIndex={-1} ref={heading}>{step === 4 ? 'Review your preferences' : 'Your search preferences'}</h1>
      <p>Tell us what you want to find. No résumé, full name or phone number is needed.</p>
      <p className="profile-notice">Saved preferences support beta compatibility matching. Existing owner Telegram alerts and daily digests keep their current rules.</p>
    </header>
    <form onSubmit={(event) => { event.preventDefault(); const problem = validate(); if (problem) setError(problem); else if (step < 4) { setError(''); setStep(step + 1); } else void save(); }}>
      <fieldset disabled={saving} className="profile-fields">
        {step === 0 && <><h2>What would you like to do?</h2>
          {input('Desired job titles', 'desiredTitles', 'Separate entries with commas. For example: Backend developer, QA engineer.')}
          {input('Role categories', 'roleCategories', 'You can enter titles, categories, or both.')}
          {input('Industries of interest', 'industries', 'Optional. Leave blank if not specified.')}</>}
        {step === 1 && <><h2>Skills and experience you have</h2>
          <p>Only include what you state yourself. These answers do not update verified résumé facts.</p>
          {input('Technical skills', 'technicalSkills', 'Optional, separated by commas.')}
          {input('Nontechnical skills', 'nontechnicalSkills', 'Optional, separated by commas.')}
          {select('Experience level', 'experienceLevel', ['UNKNOWN', 'NO_PROFESSIONAL_EXPERIENCE', 'ENTRY', 'MID', 'SENIOR', 'LEAD'])}
          <label className="profile-field">Years of experience (optional)<input type="number" min="0" max="80" step="0.01" inputMode="decimal"
            value={draft.experienceYears ?? ''} onChange={(event) => set('experienceYears', event.target.value === '' ? null : Number(event.target.value))} /></label>
          <label className="profile-field">Experience in your own words (optional)<textarea maxLength={2000} value={draft.statedExperience} onChange={(event) => set('statedExperience', event.target.value)} /></label></>}
        {step === 2 && <><h2>Location and work preferences</h2>
          {input('Current country', 'currentCountry', 'Optional. This is where you are now.')}{input('Current city', 'currentCity')}
          {input('Target countries or regions', 'targetRegions', 'Where you want to work; separate entries with commas.')}
          <fieldset className="profile-choices"><legend>Preferred work format — choose at least one</legend>
            {(['ONSITE', 'HYBRID', 'REMOTE'] as const).map((format) => <label key={format}><input type="checkbox" checked={draft.workFormats.includes(format)}
              onChange={(event) => set('workFormats', event.target.checked ? [...draft.workFormats, format] : draft.workFormats.filter((v) => v !== format))} />{format === 'ONSITE' ? 'Onsite' : format === 'HYBRID' ? 'Hybrid' : 'Remote'}</label>)}</fieldset>
          <fieldset className="profile-choices"><legend>Employment type (optional; none means not specified)</legend>
            {(['FULL_TIME', 'PART_TIME', 'INTERNSHIP', 'CONTRACT', 'TEMPORARY', 'APPRENTICESHIP', 'VOLUNTEER', 'OTHER'] as const).map((type) => <label key={type}><input type="checkbox" checked={draft.employmentTypes.includes(type)}
              onChange={(event) => set('employmentTypes', event.target.checked ? [...draft.employmentTypes, type] : draft.employmentTypes.filter((v) => v !== type))} />{type.replaceAll('_', ' ').toLowerCase()}</label>)}</fieldset>
          {select('Willing to relocate', 'relocation', ['UNKNOWN', 'YES', 'NO'])}</>}
        {step === 3 && <><h2>Languages and optional details</h2>
          <p>No languages or proficiency levels are assumed. Leave empty if not specified.</p>
          {draft.languages.map((language, index) => <fieldset className="profile-language" key={index}><legend>Language {index + 1}</legend>
            <label className="profile-field">Language name<input maxLength={120} value={language.language} onChange={(event) => set('languages', draft.languages.map((v, i) => i === index ? { ...v, language: event.target.value } : v))} /></label>
            <label className="profile-field">Proficiency<select aria-label="Proficiency" value={language.proficiency} onChange={(event) => set('languages', draft.languages.map((v, i) => i === index ? { ...v, proficiency: event.target.value as typeof language.proficiency } : v))}>
              {['UNKNOWN', 'A1', 'A2', 'B1', 'B2', 'C1', 'C2', 'NATIVE'].map((level) => <option key={level} value={level}>{level === 'UNKNOWN' ? 'Unknown / not specified' : level === 'NATIVE' ? 'Native' : level}</option>)}</select></label>
            <button className="btn btn--quiet" type="button" onClick={() => set('languages', draft.languages.filter((_, i) => i !== index))}>Remove language {index + 1}</button></fieldset>)}
          <button className="btn btn--quiet" type="button" disabled={draft.languages.length >= 20} onClick={() => set('languages', [...draft.languages, { language: '', proficiency: 'UNKNOWN' }])}>Add language</button>
          {select('Work authorization', 'workAuthorization', ['UNKNOWN', 'AUTHORIZED', 'REQUIRES_SPONSORSHIP', 'NOT_AUTHORIZED'])}
          <p>Confirm authorization separately for each country. Residence and a general authorization statement do not establish a country-specific permit.</p>
          {(draft.authorizationByCountry ?? []).map((grant, index) => <fieldset className="profile-language" key={index}><legend>Country authorization {index + 1}</legend>
            <label className="profile-field">Country code<input aria-label={`Authorization country ${index + 1}`} maxLength={2} value={grant.country} onChange={event => set('authorizationByCountry', (draft.authorizationByCountry ?? []).map((value, i) => i === index ? { ...value, country: event.target.value.toUpperCase() } : value))} /></label>
            <label className="profile-field">Authorization status<select aria-label={`Authorization status ${index + 1}`} value={grant.status} onChange={event => set('authorizationByCountry', (draft.authorizationByCountry ?? []).map((value, i) => i === index ? { ...value, status: event.target.value as typeof grant.status } : value))}>
              {(['UNKNOWN', 'AUTHORIZED', 'REQUIRES_SPONSORSHIP', 'NOT_AUTHORIZED'] as const).map(status => <option value={status} key={status}>{labels[status]}</option>)}</select></label>
            <button className="btn btn--quiet" type="button" onClick={() => set('authorizationByCountry', (draft.authorizationByCountry ?? []).filter((_, i) => i !== index))}>Remove authorization {index + 1}</button>
          </fieldset>)}
          <button className="btn btn--quiet" type="button" disabled={(draft.authorizationByCountry ?? []).length >= 40} onClick={() => set('authorizationByCountry', [...(draft.authorizationByCountry ?? []), { country: '', status: 'UNKNOWN' }])}>Add country authorization</button>
          {input('Authorization countries and details', 'authorizationDetails', 'State the countries and any limits yourself; no authorization is inferred.')}
          <label className="profile-field">Salary basis<select aria-label="Salary basis" value={draft.salaryBasis} onChange={event => set('salaryBasis', event.target.value as Draft['salaryBasis'])}><option value="UNKNOWN">Unknown / not specified</option><option value="GROSS">Gross</option><option value="NET">Net</option></select></label>
          <fieldset className="profile-language"><legend>Salary expectations (optional; leave amount, currency and period blank if not specified)</legend>
            {input('Salary amount', 'salaryAmount')}{input('Salary currency', 'salaryCurrency', 'Three-letter ISO currency, such as EUR.')}
            <label className="profile-field">Salary period<select aria-label="Salary period" value={draft.salaryPeriod} onChange={(event) => set('salaryPeriod', event.target.value as Draft['salaryPeriod'])}>
              <option value="">Not specified</option>{(['HOUR', 'DAY', 'WEEK', 'MONTH', 'YEAR'] as const).map((period) => <option value={period} key={period}>{period.toLowerCase()}</option>)}</select></label></fieldset>
          {input('Education (optional)', 'education', 'No institution name is required.')}
          {select('Student status', 'studentStatus', ['UNKNOWN', 'STUDENT', 'NOT_STUDENT'])}
          <label className="profile-field">Additional preferences (optional)<textarea maxLength={2000} value={draft.additionalPreferences} onChange={(event) => set('additionalPreferences', event.target.value)} /></label></>}
        {step === 4 && <><p>Check each answer. Saving confirms these preferences on the server.</p><dl className="profile-review">
          {(Object.keys(reviewLabels) as (keyof SearchProfile)[]).map((key) => <div key={key}><dt>{reviewLabels[key]}</dt><dd>{display(summary[key])}</dd></div>)}
        </dl></>}
      </fieldset>
      {(error || conflict) && <div role="alert" className="profile-notice"><p>{conflict
        ? 'Your saved preferences changed in another session. Your draft is still here. Load the latest saved preferences before editing again.' : error}</p>{conflict && <button type="button" className="btn btn--quiet" onClick={onReload}>Load latest saved preferences</button>}</div>}
      <div className="profile-actions">
        {step > 0 && <button className="btn btn--quiet" type="button" disabled={saving} onClick={() => { setStep(step - 1); if (!conflict) setError(''); }}>Back</button>}
        <button className="btn btn--primary" type="submit" disabled={saving || step === 4 && conflict}>{saving ? 'Saving…' : step === 4 ? 'Confirm and save' : step === 3 ? 'Review preferences' : 'Next'}</button>
        {onCancel && <button className="btn btn--quiet" type="button" disabled={saving} onClick={onCancel}>Cancel editing</button>}
      </div>
      <p className="bounded-note">{initial.createdAt ? `Last confirmed: ${new Date(initial.createdAt).toLocaleString()} · Revision ${initial.activeRevision}` : 'Not saved yet. Your draft stays in this open session.'}</p>
    </form>
  </main>;
}
