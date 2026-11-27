# 🎓 Learnora – Discover. Learn. Grow.

**Learnora** is an intelligent learning resource discovery platform designed to help learners find relevant educational resources across multiple learning platforms through a single search interface.

## 🚀 Overview

Finding quality learning resources across different platforms can be time-consuming. Learnora simplifies this process by bringing learning resources together in one place.

Users can search for any topic and discover relevant learning materials from multiple educational providers.

## ✨ Features

- 🔍 Search learning resources by any topic.
- 🌐 Discover resources from multiple learning platforms.
- 🎯 Dynamic resource retrieval through provider integrations.
- 🌍 Language-based filtering.
- 🏷️ Platform-based filtering.
- ⚡ Parallel provider searches.
- 📚 Automatic catalogue loading and caching.
- 📱 Responsive user interface.

## 🔗 Integrated Learning Providers

### Dynamic Catalogue Integrations
1. YouTube
2. NPTEL
3. IBM SkillsBuild
4. Microsoft Learn
5. freeCodeCamp
6. Kaggle Learn
7. Coursera

### External Search Integrations
8. Udemy
9. Google Skills

*Note: Udemy and Google Skills currently provide external search links rather than dynamically retrieved course catalogues.*

## 🛠️ Technology Stack

**Backend**
- Java 21
- Spring Boot
- Spring Web
- Spring Data JPA

**Frontend**
- HTML
- CSS
- JavaScript
- Bootstrap 5
- Thymeleaf

**Database**
- MySQL 8

**Tools**
- Maven
- Git
- GitHub
- Visual Studio Code

## ⚙️ Getting Started

### Prerequisites

- Java JDK 21
- MySQL 8
- Git

### 1. Clone the repository

```bash
git clone https://github.com/JeyKishore/Learnora.git
cd Learnora
```

### 2. Configure MySQL

Create the database:

```sql
CREATE DATABASE learnora_db;
```

### 3. Configure environment variables

Set the following environment variables:

```text
DB_PASSWORD=your_mysql_password
YOUTUBE_API_KEY=your_youtube_api_key
```

You can optionally configure:

```text
DB_USERNAME=root
```

### 4. Configure application properties

Refer to:

`src/main/resources/application-example.properties`

Create your local `application.properties` using the example configuration and your environment variables.

### 5. Run the application

On Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

On Linux/macOS:

```bash
./mvnw spring-boot:run
```

### 6. Open Learnora

Visit:

http://localhost:8080

## 🔐 Security

- API keys and database credentials should be stored in environment variables.
- Never commit personal credentials or secret keys.
- The local `application.properties` file is excluded through `.gitignore`.

## 📌 Project Status

Learnora is under active development. Additional learning providers, performance improvements, and deployment capabilities are planned.

## 👨‍💻 Author

**Jey Kishore K**

B.Tech – Artificial Intelligence and Data Science

CARE College of Engineering

---

**Learnora – Discover. Learn. Grow.**