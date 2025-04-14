git config --global core.autocrlf true
git pull origin master
git add -A
git commit -m "update to v6.5.0"
git push origin master
git tag -a v6.5.0 -m "v6.5.0"
git push origin --tags
pause