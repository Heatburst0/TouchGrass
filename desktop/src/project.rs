/// Parse an IDE project name locally; full titles and file names are never sent.
pub fn parse_project(app: &str, title: &str) -> Option<String> {
    let app = app.to_lowercase();
    let title = title.trim_end();
    let project = if ["code", "cursor", "windsurf"]
        .iter()
        .any(|a| app.contains(a))
    {
        let mut parts: Vec<&str> = title.split(" - ").collect();
        while parts.last().is_some_and(|part| {
            matches!(
                part.trim().to_lowercase().as_str(),
                "visual studio code" | "insiders" | "cursor" | "windsurf"
            )
        }) {
            parts.pop();
        }
        let project = strip_tag(parts.last()?.trim());
        // A lone segment is a folder name only when no file is open; a single opened
        // file (no folder) shows its file name there, which must never be uploaded.
        if parts.len() == 1 && (non_project(project) || looks_like_file(project)) {
            return None;
        }
        project
    } else if [
        "idea",
        "studio64",
        "pycharm",
        "webstorm",
        "clion",
        "goland",
        "rider",
        "rustrover",
        "phpstorm",
        "datagrip",
    ]
    .iter()
    .any(|a| app.contains(a))
    {
        strip_tag(title.split(" – ").next()?.split(" - ").next()?)
    } else {
        return None;
    };
    let project = project.trim();
    let plausible = !project.is_empty()
        && project.chars().count() <= MAX_PROJECT_CHARS
        && !project.contains(['/', '\\']);
    plausible.then(|| project.to_string())
}

const MAX_PROJECT_CHARS: usize = 80;

/// "notes.txt", "● main.rs", "Untitled" — titles that name a file, not a workspace.
fn looks_like_file(value: &str) -> bool {
    if value.starts_with('●') || value.starts_with('*') {
        return true;
    }
    value.rsplit_once('.').is_some_and(|(stem, ext)| {
        !stem.is_empty() && (1..=6).contains(&ext.len()) && ext.chars().all(|c| c.is_ascii_alphanumeric())
    })
}

/// Remove a trailing IDE remote/module annotation.
fn strip_tag(value: &str) -> &str {
    let value = value.trim();
    if value.ends_with(']') {
        if value.starts_with('[') {
            return "";
        }
        if let Some((project, _)) = value.rsplit_once(" [") {
            return project.trim();
        }
    }
    value
}

fn non_project(value: &str) -> bool {
    let value = value.to_lowercase();
    matches!(value.as_str(), "welcome" | "settings")
        || value.starts_with("extension:")
        || value.starts_with("untitled-")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn single_file_without_a_folder_is_never_a_project() {
        for title in [
            "confidential-client.txt - Visual Studio Code",
            "● notes.md - Visual Studio Code",
            "main.rs - Cursor",
        ] {
            assert_eq!(parse_project("code", title), None, "{title}");
        }
    }

    #[test]
    fn paths_and_overlong_names_are_rejected() {
        assert_eq!(parse_project("code", "a.rs - C:\\secret\\dir - Visual Studio Code"), None);
        let long = format!("a.rs - {} - Visual Studio Code", "x".repeat(81));
        assert_eq!(parse_project("code", &long), None);
    }

    #[test]
    fn folder_only_window_is_still_a_project() {
        assert_eq!(parse_project("code", "touchgrass - Visual Studio Code").as_deref(), Some("touchgrass"));
    }

    #[test]
    fn code_projects_never_use_the_file_segment() {
        for title in [
            "main.rs - touchgrass - Visual Studio Code",
            "● main.rs - touchgrass - Visual Studio Code",
            "touchgrass - Visual Studio Code",
            "main.rs - touchgrass [WSL: Ubuntu] - Visual Studio Code",
            "main.rs - touchgrass - Visual Studio Code - Insiders",
        ] {
            assert_eq!(
                parse_project("Code.exe", title).as_deref(),
                Some("touchgrass")
            );
        }
        for (app, product) in [("cursor", "Cursor"), ("windsurf", "Windsurf")] {
            assert_eq!(
                parse_project(app, &format!("file.kt - TouchGrass - {product}")).as_deref(),
                Some("TouchGrass")
            );
        }
    }

    #[test]
    fn code_non_project_windows() {
        for title in [
            "Welcome",
            "Settings",
            "Extension: Rust",
            "Untitled-1",
            "",
            "   ",
        ] {
            assert_eq!(
                parse_project("code", &format!("{title} - Visual Studio Code")),
                None
            );
        }
        assert_eq!(parse_project("code", "Visual Studio Code"), None);
    }

    #[test]
    fn jetbrains_projects_use_first_segment() {
        for app in [
            "idea64",
            "studio64",
            "pycharm",
            "webstorm",
            "clion",
            "goland",
            "rider",
            "rustrover",
            "phpstorm",
            "datagrip",
        ] {
            for title in [
                "TouchGrass – MainActivity.kt [TouchGrass.app.main]",
                "TouchGrass – MainActivity.kt",
                "TouchGrass",
                "TouchGrass - MainActivity.kt",
                " TouchGrass [module] – MainActivity.kt ",
            ] {
                assert_eq!(parse_project(app, title).as_deref(), Some("TouchGrass"));
            }
            assert_eq!(parse_project(app, "  "), None);
        }
    }

    #[test]
    fn other_apps_and_empty_projects() {
        assert_eq!(
            parse_project("browser", "file - project - Visual Studio Code"),
            None
        );
        assert_eq!(
            parse_project("code", "file -  [WSL: Ubuntu] - Visual Studio Code"),
            None
        );
        assert_eq!(
            parse_project("code", "file - Welcome - Visual Studio Code").as_deref(),
            Some("Welcome")
        );
    }
}
