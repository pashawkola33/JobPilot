export const confirmedProfile = () => ({
  activeRevision: 1, completionStatus: 'COMPLETE', missingRequiredFields: [], createdAt: new Date().toISOString(),
  profile: { desiredTitles: ['Developer'], roleCategories: [], industries: [], technicalSkills: [], nontechnicalSkills: [],
    experienceLevel: 'UNKNOWN', statedExperience: '', experienceYears: null, currentCountry: '', currentCity: '',
    targetRegions: ['Romania'], workFormats: ['REMOTE'], employmentTypes: [], languages: [], workAuthorization: 'UNKNOWN',
    authorizationDetails: '', salary: null, education: '', studentStatus: 'UNKNOWN', relocation: 'UNKNOWN', additionalPreferences: '' },
});
