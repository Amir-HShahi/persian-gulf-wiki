package queue	
type JobType string

// Package queue defines the Redis job contract shared with the Spring
// side, and runs the worker loop that consumes it
const (
	// TODO: one constant per job type the contract defines —
	// likely JobTypeNormalize, JobTypeParse, JobTypeImage, JobTypeGeo
	JobTypeRandom JobType = ""
)

// Job is one queue payload, matching the agreed contract exactly
type Job struct {
	Type JobType
	SubmissionID string
	ObjectKey string
	// TODO: remaining fields from waht amir set later
}

// Result is what a Handler reports back — shape must match the agreed
// callback/result contract
type Result struct {
	//TODO
}