# StarWars API

## Overview

The StarWars API project is a Scala-based application that provides an interface to interact with Star Wars data. It leverages the ZIO library for functional programming and offers various modules for handling API requests, data management, and search functionalities.

## Project Structure

The project is organized into multiple modules for better separation of concerns and maintainability:

- **api-client**: Handles API client functionalities.
- **data**: Manages data repositories and related operations.
- **domain**: Defines domain models and entities.
- **http-api**: Exposes HTTP endpoints for the API.
- **search**: Provides search capabilities within the Star Wars data.

## Installation

To set up the project locally, follow these steps:

1. **Clone the repository:**
   ```sh
   git clone https://github.com/matthewjones372/starwars-api.git
   cd starwars-api
Build the project:
Ensure you have SBT installed. Then, run the following command:

```sbt compile```

Run the tests:
To run the tests, use:

```sbt test```
